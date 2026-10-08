-- Phase 2: chores and their occurrences.
--
-- Recurring chores store a *rule* (recurrence_* columns), never pre-generated rows. Clients expand
-- the rule into occurrences (domain ScheduleCalculator). A chore_occurrences row exists only once
-- something happens to an occurrence: completed, skipped, snoozed or reassigned.
--
-- Chores are soft-deleted (deleted_at) so history survives and offline clients can sync deletions.
-- Fine-grained rules (who may assign/edit/delete/complete what) are enforced by triggers, because
-- RLS cannot compare old and new column values.

-- ---------------------------------------------------------------------------------------------
-- Chores
-- ---------------------------------------------------------------------------------------------

create table public.chores (
    id                 uuid primary key default gen_random_uuid(),
    household_id       uuid not null references public.households (id) on delete cascade,
    name               text not null check (char_length(btrim(name)) between 1 and 80),
    description        text check (char_length(description) <= 1000),
    category           text not null default 'OTHER' check (category ~ '^[A-Z_]{1,40}$'),
    estimated_minutes  integer not null check (estimated_minutes between 1 and 1440),
    difficulty         smallint not null default 3 check (difficulty between 1 and 5),
    points             integer not null default 0 check (points between 0 and 10000),

    -- Schedule (dates/times are local to the household's timezone)
    recurrence_type        text not null check (recurrence_type in
                               ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY_DAY', 'MONTHLY_WEEKDAY', 'YEARLY')),
    recurrence_interval    smallint not null default 1 check (recurrence_interval between 1 and 365),
    recurrence_weekdays    smallint[] not null default '{}'   -- ISO 1 = Monday .. 7 = Sunday (WEEKLY)
                               check (recurrence_weekdays <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[]),
    recurrence_month_day   smallint check (recurrence_month_day between 1 and 31),         -- MONTHLY_DAY, YEARLY
    recurrence_month       smallint check (recurrence_month between 1 and 12),             -- YEARLY
    recurrence_week_ordinal smallint check (recurrence_week_ordinal in (-1, 1, 2, 3, 4)),  -- MONTHLY_WEEKDAY
    recurrence_weekday     smallint check (recurrence_weekday between 1 and 7),            -- MONTHLY_WEEKDAY
    start_date         date not null,
    end_date           date check (end_date is null or end_date >= start_date),
    times_of_day       time[] not null default '{}',

    assignee_id        uuid references public.profiles (id) on delete set null,
    checklist          text[] not null default '{}' check (cardinality(checklist) <= 30),
    notes              text check (char_length(notes) <= 2000),
    requires_proof     boolean not null default false,
    active             boolean not null default true,

    created_by         uuid references public.profiles (id) on delete set null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    deleted_at         timestamptz,

    constraint chores_recurrence_fields check (
        case recurrence_type
            when 'WEEKLY' then cardinality(recurrence_weekdays) > 0
            when 'MONTHLY_DAY' then recurrence_month_day is not null
            when 'MONTHLY_WEEKDAY' then recurrence_week_ordinal is not null and recurrence_weekday is not null
            when 'YEARLY' then recurrence_month is not null and recurrence_month_day is not null
            else true
        end
    )
);

create index chores_household on public.chores (household_id) where deleted_at is null;
create index chores_household_updated on public.chores (household_id, updated_at);

create trigger chores_set_updated_at before update on public.chores
    for each row execute function private.set_updated_at();

create function private.is_member_of(p_household_id uuid, p_user_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.household_members where household_id = p_household_id and user_id = p_user_id)
$$;

-- Enforces per-column permissions and integrity. Skipped for privileged server-side callers
-- (no JWT subject), e.g. maintenance with the service role. Also skipped for writes made by other
-- triggers or FK actions (pg_trigger_depth() > 1), e.g. unassigning a member who left.
create function private.guard_chore_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null or pg_trigger_depth() > 1 then
        return new;
    end if;

    if tg_op = 'INSERT' then
        new.created_by := v_uid;
        new.deleted_at := null;
        if new.assignee_id is not null and new.assignee_id <> v_uid
            and not private.has_permission(new.household_id, 'ASSIGN_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
    else
        if new.household_id <> old.household_id or new.created_by is distinct from old.created_by
            or new.created_at <> old.created_at then
            raise exception 'immutable_column' using errcode = '42501';
        end if;
        if new.assignee_id is distinct from old.assignee_id
            and not private.has_permission(new.household_id, 'ASSIGN_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
        if new.deleted_at is distinct from old.deleted_at
            and not private.has_permission(new.household_id, 'DELETE_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
        -- Anything beyond assignment and deletion is an edit.
        if (to_jsonb(new) - array['assignee_id', 'deleted_at', 'updated_at'])
            is distinct from (to_jsonb(old) - array['assignee_id', 'deleted_at', 'updated_at'])
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

create trigger chores_guard_write before insert or update on public.chores
    for each row execute function private.guard_chore_write();

-- ---------------------------------------------------------------------------------------------
-- Occurrences (event records, keyed by the chore + local date + time slot)
-- ---------------------------------------------------------------------------------------------

create table public.chore_occurrences (
    id              uuid primary key default gen_random_uuid(),
    chore_id        uuid not null references public.chores (id) on delete cascade,
    household_id    uuid not null references public.households (id) on delete cascade, -- copied from the chore
    occurrence_date date not null,
    occurrence_time time,                                      -- null = all-day occurrence
    status          text not null default 'PENDING' check (status in ('PENDING', 'COMPLETED', 'SKIPPED')),
    assignee_id     uuid references public.profiles (id) on delete set null,   -- override for this occurrence
    completed_by    uuid references public.profiles (id) on delete set null,   -- who actually did it
    completed_at    timestamptz,
    snoozed_until   timestamptz,
    note            text check (char_length(note) <= 500),
    updated_by      uuid references public.profiles (id) on delete set null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint chore_occurrences_key unique nulls not distinct (chore_id, occurrence_date, occurrence_time),
    constraint chore_occurrences_completion check (status <> 'COMPLETED' or completed_at is not null)
);

create index chore_occurrences_household_date on public.chore_occurrences (household_id, occurrence_date);
create index chore_occurrences_household_updated on public.chore_occurrences (household_id, updated_at);

create trigger chore_occurrences_set_updated_at before update on public.chore_occurrences
    for each row execute function private.set_updated_at();

create function private.guard_occurrence_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_chore public.chores;
    v_role public.household_role;
    v_effective_assignee uuid;
begin
    select * into v_chore from public.chores where id = new.chore_id;
    if not found then
        raise exception 'chore_not_found' using errcode = 'P0001';
    end if;
    new.household_id := v_chore.household_id;

    if v_uid is null or pg_trigger_depth() > 1 then
        return new;
    end if;
    if v_chore.deleted_at is not null then
        raise exception 'chore_deleted' using errcode = 'P0001';
    end if;
    if tg_op = 'UPDATE' and (new.chore_id <> old.chore_id or new.occurrence_date <> old.occurrence_date
        or new.occurrence_time is distinct from old.occurrence_time) then
        raise exception 'immutable_column' using errcode = '42501';
    end if;

    v_role := private.my_role(v_chore.household_id);
    if v_role is null then
        raise exception 'permission_denied' using errcode = '42501';
    end if;

    -- Reassigning a single occurrence needs ASSIGN_CHORES and a member as the new assignee.
    if new.assignee_id is distinct from (case when tg_op = 'UPDATE' then old.assignee_id end) then
        if not private.has_permission(v_chore.household_id, 'ASSIGN_CHORES') then
            raise exception 'permission_denied' using errcode = '42501';
        end if;
        if new.assignee_id is not null and not private.is_member_of(v_chore.household_id, new.assignee_id) then
            raise exception 'assignee_not_member' using errcode = 'P0001';
        end if;
    end if;

    -- Children may only act on occurrences assigned to them.
    v_effective_assignee := coalesce(new.assignee_id, v_chore.assignee_id);
    if v_role = 'CHILD' and v_effective_assignee is distinct from v_uid then
        raise exception 'permission_denied' using errcode = '42501';
    end if;

    if new.status = 'COMPLETED' then
        -- Recording that someone else did it requires EDIT_CHORES; otherwise it is always the caller.
        if new.completed_by is null or new.completed_by = v_uid
            or not private.has_permission(v_chore.household_id, 'EDIT_CHORES') then
            new.completed_by := v_uid;
        elsif not private.is_member_of(v_chore.household_id, new.completed_by) then
            raise exception 'assignee_not_member' using errcode = 'P0001';
        end if;
        new.completed_at := coalesce(new.completed_at, now());
        if new.completed_at > now() + interval '5 minutes' then
            raise exception 'completed_in_future' using errcode = '22023';
        end if;
    else
        new.completed_by := null;
        new.completed_at := null;
    end if;

    new.updated_by := v_uid;
    return new;
end;
$$;

create trigger chore_occurrences_guard_write before insert or update on public.chore_occurrences
    for each row execute function private.guard_occurrence_write();

-- When someone leaves (or is removed from) a household, unassign their chores there.
create function private.unassign_departed_member() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    update public.chores set assignee_id = null
    where household_id = old.household_id and assignee_id = old.user_id;
    update public.chore_occurrences set assignee_id = null
    where household_id = old.household_id and assignee_id = old.user_id and status = 'PENDING';
    return null;
end;
$$;

create trigger household_members_unassign after delete on public.household_members
    for each row execute function private.unassign_departed_member();

-- ---------------------------------------------------------------------------------------------
-- Grants & RLS
-- ---------------------------------------------------------------------------------------------

revoke all on public.chores, public.chore_occurrences from anon;
-- Chores are soft-deleted; occurrences are reset to PENDING rather than deleted.
revoke delete on public.chores, public.chore_occurrences from authenticated;
revoke all on all functions in schema private from public, anon;
grant execute on all functions in schema private to authenticated, service_role;

alter table public.chores enable row level security;
alter table public.chore_occurrences enable row level security;

create policy "Members see household chores" on public.chores
    for select to authenticated
    using (private.is_member(household_id));

create policy "Chore creators add chores" on public.chores
    for insert to authenticated
    with check (private.has_permission(household_id, 'CREATE_CHORES'));

-- Column-level rules (edit vs assign vs delete) are enforced by chores_guard_write.
create policy "Chore managers update chores" on public.chores
    for update to authenticated
    using (private.has_permission(household_id, 'EDIT_CHORES')
           or private.has_permission(household_id, 'ASSIGN_CHORES')
           or private.has_permission(household_id, 'DELETE_CHORES'))
    with check (private.is_member(household_id));

create policy "Members see occurrences" on public.chore_occurrences
    for select to authenticated
    using (private.is_member(household_id));

create policy "Members record occurrences" on public.chore_occurrences
    for insert to authenticated
    with check (private.is_member(household_id));

create policy "Members update occurrences" on public.chore_occurrences
    for update to authenticated
    using (private.is_member(household_id))
    with check (private.is_member(household_id));
