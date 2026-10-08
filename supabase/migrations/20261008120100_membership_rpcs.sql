-- Phase 1: membership operations. All rules live here so every client gets identical behaviour.
-- Errors: SQLSTATE 42501 for permission failures (HTTP 403 via PostgREST); P0001 with a stable
-- snake_case message key for business-rule failures (mapped to friendly text by the client).

create function public.create_household(p_name text, p_timezone text default 'UTC') returns uuid
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_id  uuid;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if exists (select 1 from public.profiles where id = v_uid and is_child) then
        raise exception 'children_cannot_create_households' using errcode = '42501';
    end if;

    insert into public.households (name, timezone, created_by)
    values (btrim(p_name), coalesce(nullif(btrim(p_timezone), ''), 'UTC'), v_uid)
    returning id into v_id;

    insert into public.household_members (household_id, user_id, role) values (v_id, v_uid, 'OWNER');

    insert into public.household_role_permissions (household_id, role, permission)
    select v_id, d.role, d.permission from private.default_role_permissions() d;

    return v_id;
end;
$$;

create function public.leave_household(p_household_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_uid  uuid := auth.uid();
    v_role public.household_role := private.my_role(p_household_id);
begin
    if v_role is null then
        raise exception 'not_a_member' using errcode = 'P0001';
    end if;
    if v_role = 'CHILD' then
        raise exception 'children_cannot_leave' using errcode = '42501';
    end if;
    if v_role = 'OWNER' and exists (select 1 from public.household_members
                                    where household_id = p_household_id and user_id <> v_uid) then
        raise exception 'transfer_ownership_required' using errcode = 'P0001';
    end if;
    -- A sole owner leaving deletes the household (via the delete_empty_household trigger).
    delete from public.household_members where household_id = p_household_id and user_id = v_uid;
end;
$$;

create function public.remove_household_member(p_household_id uuid, p_user_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
    if p_user_id = auth.uid() then
        raise exception 'use_leave_household' using errcode = 'P0001';
    end if;
    if not private.has_permission(p_household_id, 'REMOVE_MEMBERS')
        or not private.can_manage_member(p_household_id, p_user_id) then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    delete from public.household_members where household_id = p_household_id and user_id = p_user_id;
end;
$$;

create function public.change_member_role(p_household_id uuid, p_user_id uuid, p_role public.household_role)
returns void
language plpgsql security definer set search_path = '' as $$
begin
    if p_role = 'OWNER' then
        raise exception 'use_transfer_ownership' using errcode = 'P0001';
    end if;
    if p_user_id = auth.uid()
        or not private.can_manage_member(p_household_id, p_user_id)
        or not private.can_manage_role(p_household_id, p_role) then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    if exists (select 1 from public.profiles where id = p_user_id and is_child) and p_role <> 'CHILD' then
        raise exception 'child_accounts_must_be_child_role' using errcode = 'P0001';
    end if;
    update public.household_members set role = p_role
    where household_id = p_household_id and user_id = p_user_id;
end;
$$;

create function public.transfer_household_ownership(p_household_id uuid, p_new_owner uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
begin
    if private.my_role(p_household_id) is distinct from 'OWNER' then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    if p_new_owner = v_uid or not exists (select 1 from public.household_members
                                          where household_id = p_household_id and user_id = p_new_owner) then
        raise exception 'not_a_member' using errcode = 'P0001';
    end if;
    if exists (select 1 from public.profiles where id = p_new_owner and is_child) then
        raise exception 'children_cannot_own_households' using errcode = 'P0001';
    end if;
    -- Demote first: at most one OWNER per household is enforced by a unique index.
    update public.household_members set role = 'ADMIN' where household_id = p_household_id and user_id = v_uid;
    update public.household_members set role = 'OWNER' where household_id = p_household_id and user_id = p_new_owner;
    delete from public.household_member_permission_overrides
    where household_id = p_household_id and user_id = p_new_owner;
end;
$$;

-- Called by the delete-account Edge Function (as the user) before the auth user is removed.
-- Deletes households where the caller is the only member; refuses if they own a shared household.
create function public.prepare_account_deletion() returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_blocking text;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if exists (select 1 from public.profiles where id = v_uid and is_child) then
        raise exception 'children_cannot_delete_account' using errcode = '42501';
    end if;
    select string_agg(h.name, ', ' order by h.name) into v_blocking
    from public.household_members m
    join public.households h on h.id = m.household_id
    where m.user_id = v_uid and m.role = 'OWNER'
      and exists (select 1 from public.household_members o where o.household_id = m.household_id and o.user_id <> v_uid);
    if v_blocking is not null then
        raise exception 'transfer_ownership_required' using errcode = 'P0001', detail = v_blocking;
    end if;
    delete from public.households h
    where exists (select 1 from public.household_members m where m.household_id = h.id and m.user_id = v_uid)
      and not exists (select 1 from public.household_members m where m.household_id = h.id and m.user_id <> v_uid);
end;
$$;

revoke execute on function
    public.create_household(text, text),
    public.leave_household(uuid),
    public.remove_household_member(uuid, uuid),
    public.change_member_role(uuid, uuid, public.household_role),
    public.transfer_household_ownership(uuid, uuid),
    public.prepare_account_deletion(),
    public.has_household_permission(uuid, public.household_permission)
from public, anon;

grant execute on function
    public.create_household(text, text),
    public.leave_household(uuid),
    public.remove_household_member(uuid, uuid),
    public.change_member_role(uuid, uuid, public.household_role),
    public.transfer_household_ownership(uuid, uuid),
    public.prepare_account_deletion(),
    public.has_household_permission(uuid, public.household_permission)
to authenticated, service_role;
