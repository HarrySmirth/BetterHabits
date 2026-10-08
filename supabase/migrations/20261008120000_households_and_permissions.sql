-- Phase 1: profiles, households, membership, roles and permissions.
--
-- Design:
--  * Every household is isolated; access is decided by RLS using helpers in the `private` schema
--    (not exposed through the API).
--  * Membership changes go through SECURITY DEFINER RPCs (next migrations) that enforce the rules;
--    clients cannot write household_members directly.
--  * Permissions = per-household role defaults (household_role_permissions) + per-member overrides.
--    OWNER always has every permission.

create schema if not exists private;
grant usage on schema private to authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- Types
-- ---------------------------------------------------------------------------------------------

create type public.household_role as enum ('OWNER', 'ADMIN', 'MEMBER', 'CHILD');

-- Extend with `alter type ... add value` in later migrations; clients ignore unknown values.
create type public.household_permission as enum (
    'CREATE_CHORES',
    'EDIT_CHORES',
    'DELETE_CHORES',
    'ASSIGN_CHORES',
    'MANAGE_TEMPLATES',
    'MANAGE_SETTINGS',
    'INVITE_MEMBERS',
    'REMOVE_MEMBERS',
    'MANAGE_CHILDREN',
    'CONFIGURE_ALLOCATION'
);

-- ---------------------------------------------------------------------------------------------
-- Generic triggers
-- ---------------------------------------------------------------------------------------------

create function private.set_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create function private.validate_timezone() returns trigger
language plpgsql set search_path = '' as $$
begin
    if not exists (select 1 from pg_catalog.pg_timezone_names where name = new.timezone) then
        raise exception 'invalid_timezone' using errcode = '22023';
    end if;
    return new;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Profiles (1:1 with auth.users, created by trigger)
-- ---------------------------------------------------------------------------------------------

create table public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    display_name text not null check (char_length(btrim(display_name)) between 1 and 50),
    timezone     text not null default 'UTC',
    -- Set only from app_metadata (service role), never from user-editable metadata.
    is_child     boolean not null default false,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create trigger profiles_set_updated_at before update on public.profiles
    for each row execute function private.set_updated_at();
create trigger profiles_validate_timezone before insert or update of timezone on public.profiles
    for each row execute function private.validate_timezone();

create function private.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id, display_name, is_child)
    values (
        new.id,
        left(coalesce(
            nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
            nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
            nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
            nullif(split_part(coalesce(new.email, ''), '@', 1), ''),
            'Member'
        ), 50),
        coalesce((new.raw_app_meta_data ->> 'is_child')::boolean, false)
    );
    return new;
end;
$$;

create trigger on_auth_user_created after insert on auth.users
    for each row execute function private.handle_new_user();

-- ---------------------------------------------------------------------------------------------
-- Households and membership
-- ---------------------------------------------------------------------------------------------

create table public.households (
    id         uuid primary key default gen_random_uuid(),
    name       text not null check (char_length(btrim(name)) between 1 and 60),
    timezone   text not null default 'UTC',
    created_by uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create trigger households_set_updated_at before update on public.households
    for each row execute function private.set_updated_at();
create trigger households_validate_timezone before insert or update of timezone on public.households
    for each row execute function private.validate_timezone();

create table public.household_members (
    household_id uuid not null references public.households (id) on delete cascade,
    -- References profiles (whose id is the auth user id, cascading from auth.users) so PostgREST
    -- can embed member profiles in one request.
    user_id      uuid not null references public.profiles (id) on delete cascade,
    role         public.household_role not null,
    joined_at    timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    primary key (household_id, user_id)
);

create unique index household_members_one_owner on public.household_members (household_id) where role = 'OWNER';
create index household_members_user_id on public.household_members (user_id);

create trigger household_members_set_updated_at before update on public.household_members
    for each row execute function private.set_updated_at();

-- Never leave a household with members but no owner (e.g. an owner's account being deleted).
create function private.guard_owner_removal() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if old.role = 'OWNER'
        and exists (select 1 from public.households where id = old.household_id)
        and exists (select 1 from public.household_members
                    where household_id = old.household_id and user_id <> old.user_id) then
        raise exception 'transfer_ownership_required' using errcode = 'P0001',
            hint = 'Transfer ownership before the owner leaves or deletes their account.';
    end if;
    return old;
end;
$$;

create trigger household_members_guard_owner before delete on public.household_members
    for each row execute function private.guard_owner_removal();

-- A household whose last member is gone is deleted rather than orphaned.
create function private.delete_empty_household() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.households h
    where h.id = old.household_id
      and not exists (select 1 from public.household_members where household_id = old.household_id);
    return null;
end;
$$;

create trigger household_members_delete_empty after delete on public.household_members
    for each row execute function private.delete_empty_household();

-- ---------------------------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------------------------

-- Presence of a row = the role has the permission in that household.
create table public.household_role_permissions (
    household_id uuid not null references public.households (id) on delete cascade,
    role         public.household_role not null check (role <> 'OWNER'),
    permission   public.household_permission not null,
    primary key (household_id, role, permission)
);

create table public.household_member_permission_overrides (
    household_id uuid not null,
    user_id      uuid not null,
    permission   public.household_permission not null,
    granted      boolean not null,
    primary key (household_id, user_id, permission),
    foreign key (household_id, user_id) references public.household_members (household_id, user_id) on delete cascade
);

-- Defaults applied when a household is created. Admins get everything; members can manage
-- chores, templates and invite; children get nothing beyond doing their own chores/habits.
create function private.default_role_permissions()
returns table (role public.household_role, permission public.household_permission)
language sql immutable set search_path = '' as $$
    select 'ADMIN'::public.household_role, p
    from unnest(enum_range(null::public.household_permission)) as p
    union all
    select 'MEMBER'::public.household_role, p
    from unnest(array['CREATE_CHORES', 'EDIT_CHORES', 'DELETE_CHORES', 'ASSIGN_CHORES',
                      'MANAGE_TEMPLATES', 'INVITE_MEMBERS']::public.household_permission[]) as p
$$;

-- ---------------------------------------------------------------------------------------------
-- RLS helpers (SECURITY DEFINER so policies on household_members don't recurse)
-- ---------------------------------------------------------------------------------------------

create function private.my_role(p_household_id uuid) returns public.household_role
language sql stable security definer set search_path = '' as $$
    select role from public.household_members
    where household_id = p_household_id and user_id = (select auth.uid())
$$;

create function private.is_member(p_household_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.household_members
                   where household_id = p_household_id and user_id = (select auth.uid()))
$$;

create function private.has_permission(p_household_id uuid, p_permission public.household_permission)
returns boolean
language sql stable security definer set search_path = '' as $$
    select coalesce((
        select case
            when m.role = 'OWNER' then true
            when o.granted is not null then o.granted
            else exists (select 1 from public.household_role_permissions rp
                         where rp.household_id = m.household_id and rp.role = m.role
                           and rp.permission = p_permission)
        end
        from public.household_members m
        left join public.household_member_permission_overrides o
            on o.household_id = m.household_id and o.user_id = m.user_id and o.permission = p_permission
        where m.household_id = p_household_id and m.user_id = (select auth.uid())
    ), false)
$$;

create function private.shares_household_with(p_user_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1 from public.household_members a
        join public.household_members b on a.household_id = b.household_id
        where a.user_id = (select auth.uid()) and b.user_id = p_user_id
    )
$$;

-- Owners manage everyone except themselves; admins manage members and children only.
create function private.can_manage_member(p_household_id uuid, p_user_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select case private.my_role(p_household_id)
        when 'OWNER' then coalesce((select role <> 'OWNER' from public.household_members
                                    where household_id = p_household_id and user_id = p_user_id), false)
        when 'ADMIN' then coalesce((select role in ('MEMBER', 'CHILD') from public.household_members
                                    where household_id = p_household_id and user_id = p_user_id), false)
        else false
    end
$$;

create function private.can_manage_role(p_household_id uuid, p_role public.household_role) returns boolean
language sql stable security definer set search_path = '' as $$
    select case private.my_role(p_household_id)
        when 'OWNER' then p_role <> 'OWNER'
        when 'ADMIN' then p_role in ('MEMBER', 'CHILD')
        else false
    end
$$;

revoke all on all functions in schema private from public, anon;
grant execute on all functions in schema private to authenticated, service_role;

-- Exposed so the app (and Edge Functions acting as the caller) can ask about the *caller's* rights.
create function public.has_household_permission(p_household_id uuid, p_permission public.household_permission)
returns boolean
language sql stable set search_path = '' as $$
    select private.has_permission(p_household_id, p_permission)
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants & RLS
-- ---------------------------------------------------------------------------------------------

revoke all on public.profiles, public.households, public.household_members,
    public.household_role_permissions, public.household_member_permission_overrides from anon;

-- Profiles: created by trigger, deleted by cascade; users may edit only name and timezone.
revoke insert, update, delete on public.profiles from authenticated;
grant update (display_name, timezone) on public.profiles to authenticated;
-- Households: created via create_household(); settings editable by column.
revoke insert, update on public.households from authenticated;
grant update (name, timezone) on public.households to authenticated;
-- Membership: RPCs only.
revoke insert, update, delete on public.household_members from authenticated;

alter table public.profiles enable row level security;
alter table public.households enable row level security;
alter table public.household_members enable row level security;
alter table public.household_role_permissions enable row level security;
alter table public.household_member_permission_overrides enable row level security;

create policy "Profiles visible to self and household mates" on public.profiles
    for select to authenticated
    using (id = (select auth.uid()) or private.shares_household_with(id));

create policy "Users update their own profile" on public.profiles
    for update to authenticated
    using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy "Members see their households" on public.households
    for select to authenticated
    using (private.is_member(id));

create policy "Settings managers update households" on public.households
    for update to authenticated
    using (private.has_permission(id, 'MANAGE_SETTINGS'))
    with check (private.has_permission(id, 'MANAGE_SETTINGS'));

create policy "Owners delete households" on public.households
    for delete to authenticated
    using (private.my_role(id) = 'OWNER');

create policy "Members see fellow members" on public.household_members
    for select to authenticated
    using (private.is_member(household_id));

create policy "Members see role permissions" on public.household_role_permissions
    for select to authenticated
    using (private.is_member(household_id));

create policy "Owners/admins grant role permissions" on public.household_role_permissions
    for insert to authenticated
    with check (private.can_manage_role(household_id, role));

create policy "Owners/admins revoke role permissions" on public.household_role_permissions
    for delete to authenticated
    using (private.can_manage_role(household_id, role));

create policy "Overrides visible to the member and managers" on public.household_member_permission_overrides
    for select to authenticated
    using (private.is_member(household_id)
           and (user_id = (select auth.uid()) or private.my_role(household_id) in ('OWNER', 'ADMIN')));

create policy "Managers add overrides" on public.household_member_permission_overrides
    for insert to authenticated
    with check (private.can_manage_member(household_id, user_id));

create policy "Managers change overrides" on public.household_member_permission_overrides
    for update to authenticated
    using (private.can_manage_member(household_id, user_id))
    with check (private.can_manage_member(household_id, user_id));

create policy "Managers remove overrides" on public.household_member_permission_overrides
    for delete to authenticated
    using (private.can_manage_member(household_id, user_id));
