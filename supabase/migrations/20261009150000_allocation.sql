-- Phase 4: preferences, availability, fairness settings and allocation controls.
--
-- The allocation engine runs on the client (deterministic, explainable, testable); the server stores
-- its inputs and enforces who may change them:
--  * preferences / availability / away periods: your own, or a child's if you can MANAGE_CHILDREN
--  * household allocation settings and each member's fair share: CONFIGURE_ALLOCATION
--  * a chore's assignment controls (assignee, source, lock, exclusions, rotation): ASSIGN_CHORES

-- ---------------------------------------------------------------------------------------------
-- Fair share per member (1 = standard; e.g. 0.5 for a child doing half as much)
-- ---------------------------------------------------------------------------------------------

alter table public.household_members
    add column workload_share numeric(3, 2) not null default 1.00 check (workload_share between 0.10 and 3.00);

create function public.set_member_workload_share(p_household_id uuid, p_user_id uuid, p_share numeric) returns void
language plpgsql security definer set search_path = '' as $$
begin
    if not private.has_permission(p_household_id, 'CONFIGURE_ALLOCATION') then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    update public.household_members set workload_share = p_share
    where household_id = p_household_id and user_id = p_user_id;
    if not found then
        raise exception 'not_a_member' using errcode = 'P0001';
    end if;
end;
$$;

revoke execute on function public.set_member_workload_share(uuid, uuid, numeric) from public, anon;
grant execute on function public.set_member_workload_share(uuid, uuid, numeric) to authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- Household allocation settings (absent row = defaults)
-- ---------------------------------------------------------------------------------------------

create table public.household_allocation_settings (
    household_id      uuid primary key references public.households (id) on delete cascade,
    preference_weight smallint not null default 50 check (preference_weight between 0 and 100),
    allow_avoidance   boolean not null default false,
    updated_at        timestamptz not null default now()
);

create trigger household_allocation_settings_set_updated_at before update on public.household_allocation_settings
    for each row execute function private.set_updated_at();

-- ---------------------------------------------------------------------------------------------
-- Per-member preferences and availability
-- ---------------------------------------------------------------------------------------------

-- Own data, or a child's when you manage children.
create function private.can_edit_member_data(p_household_id uuid, p_user_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select p_user_id = (select auth.uid())
        or (private.has_permission(p_household_id, 'MANAGE_CHILDREN')
            and exists (select 1 from public.household_members
                        where household_id = p_household_id and user_id = p_user_id and role = 'CHILD'))
$$;

create table public.member_preferences (
    household_id uuid not null,
    user_id      uuid not null,
    target_type  text not null check (target_type in ('CHORE', 'CATEGORY')),
    -- A chore id or a category name (categories are text so new ones need no migration).
    target       text not null check (char_length(target) between 1 and 64),
    level        text not null check (level in ('LOVE', 'LIKE', 'NEUTRAL', 'DISLIKE', 'HATE', 'CANNOT_DO')),
    updated_at   timestamptz not null default now(),
    primary key (household_id, user_id, target_type, target),
    foreign key (household_id, user_id) references public.household_members (household_id, user_id) on delete cascade
);

create table public.member_availability (
    household_id     uuid not null,
    user_id          uuid not null,
    unavailable_days smallint[] not null default '{}' check (unavailable_days <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[]),
    preferred_times  text[] not null default '{}' check (preferred_times <@ array['MORNING', 'AFTERNOON', 'EVENING']),
    updated_at       timestamptz not null default now(),
    primary key (household_id, user_id),
    foreign key (household_id, user_id) references public.household_members (household_id, user_id) on delete cascade
);

create table public.member_away_periods (
    id           uuid primary key default gen_random_uuid(),
    household_id uuid not null,
    user_id      uuid not null,
    start_date   date not null,
    end_date     date not null check (end_date >= start_date),
    note         text check (char_length(note) <= 200),
    foreign key (household_id, user_id) references public.household_members (household_id, user_id) on delete cascade
);

create index member_away_periods_member on public.member_away_periods (household_id, user_id);

create trigger member_preferences_set_updated_at before update on public.member_preferences
    for each row execute function private.set_updated_at();
create trigger member_availability_set_updated_at before update on public.member_availability
    for each row execute function private.set_updated_at();

-- ---------------------------------------------------------------------------------------------
-- Chore assignment controls
-- ---------------------------------------------------------------------------------------------

alter table public.chores
    add column assignment_source   text not null default 'MANUAL' check (assignment_source in ('MANUAL', 'AUTO')),
    add column assignment_locked   boolean not null default false,
    add column excluded_member_ids uuid[] not null default '{}',
    add column rotate              boolean not null default false;

-- Same rules as before, with the assignment controls grouped under ASSIGN_CHORES.
create or replace function private.guard_chore_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_assignment_columns constant text[] :=
        array['assignee_id', 'assignment_source', 'assignment_locked', 'excluded_member_ids', 'rotate'];
begin
    if v_uid is null or pg_trigger_depth() > 1 then
        return new;
    end if;

    if tg_op = 'INSERT' then
        new.created_by := v_uid;
        new.deleted_at := null;
        if (new.assignee_id is not null and new.assignee_id <> v_uid
            or new.assignment_locked or cardinality(new.excluded_member_ids) > 0 or new.rotate)
            and not private.has_permission(new.household_id, 'ASSIGN_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
    else
        if new.household_id <> old.household_id or new.created_by is distinct from old.created_by
            or new.created_at <> old.created_at then
            raise exception 'immutable_column' using errcode = '42501';
        end if;
        if (select bool_or(to_jsonb(new) -> c is distinct from to_jsonb(old) -> c) from unnest(v_assignment_columns) c)
            and not private.has_permission(new.household_id, 'ASSIGN_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
        if new.deleted_at is distinct from old.deleted_at
            and not private.has_permission(new.household_id, 'DELETE_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
        -- Anything beyond assignment and deletion is an edit.
        if (to_jsonb(new) - v_assignment_columns - array['deleted_at', 'updated_at'])
            is distinct from (to_jsonb(old) - v_assignment_columns - array['deleted_at', 'updated_at'])
            and not private.has_permission(new.household_id, 'EDIT_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
    end if;

    if new.assignee_id is not null and not private.is_member_of(new.household_id, new.assignee_id) then
        raise exception 'assignee_not_member' using errcode = 'P0001';
    end if;
    return new;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants & RLS
-- ---------------------------------------------------------------------------------------------

revoke all on public.household_allocation_settings, public.member_preferences,
    public.member_availability, public.member_away_periods from anon;
revoke all on all functions in schema private from public, anon;
grant execute on all functions in schema private to authenticated, service_role;

alter table public.household_allocation_settings enable row level security;
alter table public.member_preferences enable row level security;
alter table public.member_availability enable row level security;
alter table public.member_away_periods enable row level security;

create policy "Members see allocation settings" on public.household_allocation_settings
    for select to authenticated using (private.is_member(household_id));
create policy "Allocation managers create settings" on public.household_allocation_settings
    for insert to authenticated with check (private.has_permission(household_id, 'CONFIGURE_ALLOCATION'));
create policy "Allocation managers update settings" on public.household_allocation_settings
    for update to authenticated
    using (private.has_permission(household_id, 'CONFIGURE_ALLOCATION'))
    with check (private.has_permission(household_id, 'CONFIGURE_ALLOCATION'));

-- Everyone in the household can read preferences/availability: the allocator needs them.
create policy "Members see preferences" on public.member_preferences
    for select to authenticated using (private.is_member(household_id));
create policy "Members manage their own (or their children's) preferences" on public.member_preferences
    for all to authenticated
    using (private.can_edit_member_data(household_id, user_id))
    with check (private.can_edit_member_data(household_id, user_id));

create policy "Members see availability" on public.member_availability
    for select to authenticated using (private.is_member(household_id));
create policy "Members manage their own (or their children's) availability" on public.member_availability
    for all to authenticated
    using (private.can_edit_member_data(household_id, user_id))
    with check (private.can_edit_member_data(household_id, user_id));

create policy "Members see away periods" on public.member_away_periods
    for select to authenticated using (private.is_member(household_id));
create policy "Members manage their own (or their children's) away periods" on public.member_away_periods
    for all to authenticated
    using (private.can_edit_member_data(household_id, user_id))
    with check (private.can_edit_member_data(household_id, user_id));
