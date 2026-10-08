-- Phase 1: joining households.
--  * Invite codes: short random codes (8 chars from an unambiguous 31-char alphabet, ~40 bits) that
--    expire, can be revoked, optionally limit uses, and are rate-limited per user. Redeeming reveals
--    nothing about a household unless the code is valid.
--  * Email invitations: bound to an email address; the invitee sees and accepts them in-app once
--    signed in with that (verified) address.

-- ---------------------------------------------------------------------------------------------
-- Invite codes
-- ---------------------------------------------------------------------------------------------

create table public.household_invite_codes (
    id           uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households (id) on delete cascade,
    code         text not null unique check (code ~ '^[2-9ABCDEFGHJKMNPQRSTUVWXYZ]{8}$'),
    created_by   uuid references auth.users (id) on delete set null,
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null,
    max_uses     integer check (max_uses is null or max_uses > 0),
    use_count    integer not null default 0,
    revoked_at   timestamptz
);

create index household_invite_codes_household on public.household_invite_codes (household_id);

create table private.invite_code_attempts (
    user_id      uuid not null references auth.users (id) on delete cascade,
    attempted_at timestamptz not null default now(),
    success      boolean not null
);

create index invite_code_attempts_user_time on private.invite_code_attempts (user_id, attempted_at);

-- Cryptographically random code. gen_random_uuid() uses the server's strong RNG; fixed UUID
-- version/variant bytes are skipped and rejection sampling avoids modulo bias.
create function private.random_invite_code() returns text
language plpgsql volatile set search_path = '' as $$
declare
    alphabet constant text := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    v_code text := '';
    v_bytes bytea;
    v_byte int;
begin
    while char_length(v_code) < 8 loop
        v_bytes := uuid_send(gen_random_uuid());
        for i in 0..15 loop
            continue when i in (6, 8);
            v_byte := get_byte(v_bytes, i);
            continue when v_byte >= 248; -- 248 = 31 * 8
            v_code := v_code || substr(alphabet, (v_byte % 31) + 1, 1);
            exit when char_length(v_code) = 8;
        end loop;
    end loop;
    return v_code;
end;
$$;

create function public.create_invite_code(
    p_household_id uuid,
    p_valid_hours integer default 72,
    p_max_uses integer default null
) returns public.household_invite_codes
language plpgsql security definer set search_path = '' as $$
declare
    v_row public.household_invite_codes;
begin
    if not private.has_permission(p_household_id, 'INVITE_MEMBERS') then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    if p_valid_hours not between 1 and 720 then
        raise exception 'invalid_expiry' using errcode = '22023';
    end if;
    for attempt in 1..5 loop
        begin
            insert into public.household_invite_codes (household_id, code, created_by, expires_at, max_uses)
            values (p_household_id, private.random_invite_code(), auth.uid(),
                    now() + make_interval(hours => p_valid_hours), p_max_uses)
            returning * into v_row;
            return v_row;
        exception when unique_violation then
            -- astronomically unlikely collision: try a new code
        end;
    end loop;
    raise exception 'code_generation_failed' using errcode = 'P0001';
end;
$$;

create function public.revoke_invite_code(p_code_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_household uuid;
begin
    select household_id into v_household from public.household_invite_codes where id = p_code_id;
    if v_household is null or not private.has_permission(v_household, 'INVITE_MEMBERS') then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    update public.household_invite_codes set revoked_at = now() where id = p_code_id and revoked_at is null;
end;
$$;

-- Returns {"status": joined|already_member|invalid|expired|rate_limited, "household_id": uuid?}.
-- Failures are returned (not raised) so the attempt is recorded for rate limiting.
create function public.join_household_with_code(p_code text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
    v_row public.household_invite_codes;
    v_failures int;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if exists (select 1 from public.profiles where id = v_uid and is_child) then
        raise exception 'children_cannot_join' using errcode = '42501';
    end if;

    delete from private.invite_code_attempts where user_id = v_uid and attempted_at < now() - interval '1 day';
    select count(*) into v_failures from private.invite_code_attempts
    where user_id = v_uid and not success and attempted_at > now() - interval '1 hour';
    if v_failures >= 10 then
        return jsonb_build_object('status', 'rate_limited');
    end if;

    select * into v_row from public.household_invite_codes where code = v_code for update;
    if not found or v_row.revoked_at is not null then
        insert into private.invite_code_attempts (user_id, success) values (v_uid, false);
        return jsonb_build_object('status', 'invalid');
    end if;
    if v_row.expires_at <= now() or (v_row.max_uses is not null and v_row.use_count >= v_row.max_uses) then
        insert into private.invite_code_attempts (user_id, success) values (v_uid, false);
        return jsonb_build_object('status', 'expired');
    end if;

    insert into private.invite_code_attempts (user_id, success) values (v_uid, true);
    if exists (select 1 from public.household_members where household_id = v_row.household_id and user_id = v_uid) then
        return jsonb_build_object('status', 'already_member', 'household_id', v_row.household_id);
    end if;

    insert into public.household_members (household_id, user_id, role) values (v_row.household_id, v_uid, 'MEMBER');
    update public.household_invite_codes set use_count = use_count + 1 where id = v_row.id;
    return jsonb_build_object('status', 'joined', 'household_id', v_row.household_id);
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Email invitations
-- ---------------------------------------------------------------------------------------------

create type public.invitation_status as enum ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED');

create table public.household_invitations (
    id           uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households (id) on delete cascade,
    email        text not null check (email = lower(btrim(email)) and email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    role         public.household_role not null default 'MEMBER' check (role in ('ADMIN', 'MEMBER')),
    invited_by   uuid references auth.users (id) on delete set null,
    status       public.invitation_status not null default 'PENDING',
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null default now() + interval '14 days',
    responded_at timestamptz
);

create unique index household_invitations_one_pending on public.household_invitations (household_id, email)
    where status = 'PENDING';
create index household_invitations_email on public.household_invitations (email) where status = 'PENDING';

create function public.create_invitation(
    p_household_id uuid,
    p_email text,
    p_role public.household_role default 'MEMBER'
) returns public.household_invitations
language plpgsql security definer set search_path = '' as $$
declare
    v_email text := lower(btrim(p_email));
    v_row public.household_invitations;
begin
    if not private.has_permission(p_household_id, 'INVITE_MEMBERS') then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    if p_role not in ('ADMIN', 'MEMBER') then
        raise exception 'invalid_role' using errcode = '22023';
    end if;
    if p_role = 'ADMIN' and private.my_role(p_household_id) <> 'OWNER' then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    if exists (select 1 from public.household_members m join auth.users u on u.id = m.user_id
               where m.household_id = p_household_id and lower(u.email) = v_email) then
        raise exception 'already_member' using errcode = 'P0001';
    end if;

    -- Re-inviting refreshes the existing pending invitation rather than duplicating it.
    update public.household_invitations
    set role = p_role, invited_by = auth.uid(), expires_at = now() + interval '14 days'
    where household_id = p_household_id and email = v_email and status = 'PENDING'
    returning * into v_row;
    if found then
        return v_row;
    end if;

    insert into public.household_invitations (household_id, email, role, invited_by)
    values (p_household_id, v_email, p_role, auth.uid())
    returning * into v_row;
    return v_row;
end;
$$;

create function public.revoke_invitation(p_invitation_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_household uuid;
begin
    select household_id into v_household from public.household_invitations where id = p_invitation_id;
    if v_household is null or not private.has_permission(v_household, 'INVITE_MEMBERS') then
        raise exception 'permission_denied' using errcode = '42501';
    end if;
    update public.household_invitations set status = 'REVOKED', responded_at = now()
    where id = p_invitation_id and status = 'PENDING';
end;
$$;

-- Invitations addressed to the signed-in user's email, with just enough context to decide.
create function public.my_pending_invitations()
returns table (
    id uuid,
    household_id uuid,
    household_name text,
    invited_by_name text,
    role public.household_role,
    expires_at timestamptz
)
language sql stable security definer set search_path = '' as $$
    select i.id, i.household_id, h.name, p.display_name, i.role, i.expires_at
    from public.household_invitations i
    join public.households h on h.id = i.household_id
    left join public.profiles p on p.id = i.invited_by
    where i.email = lower(auth.jwt() ->> 'email')
      and i.status = 'PENDING'
      and i.expires_at > now()
    order by i.created_at desc
$$;

create function public.respond_to_invitation(p_invitation_id uuid, p_accept boolean) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
    v_row public.household_invitations;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    select * into v_row from public.household_invitations
    where id = p_invitation_id and email = lower(auth.jwt() ->> 'email') and status = 'PENDING'
    for update;
    if not found then
        raise exception 'invitation_not_found' using errcode = 'P0001';
    end if;
    if v_row.expires_at <= now() then
        raise exception 'invitation_expired' using errcode = 'P0001';
    end if;

    update public.household_invitations
    set status = case when p_accept then 'ACCEPTED'::public.invitation_status else 'DECLINED' end,
        responded_at = now()
    where id = v_row.id;

    if p_accept then
        insert into public.household_members (household_id, user_id, role)
        values (v_row.household_id, v_uid, v_row.role)
        on conflict (household_id, user_id) do nothing;
    end if;
    return v_row.household_id;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants & RLS
-- ---------------------------------------------------------------------------------------------

revoke all on public.household_invite_codes, public.household_invitations from anon;
revoke insert, update, delete on public.household_invite_codes, public.household_invitations from authenticated;
revoke all on private.invite_code_attempts from public, anon, authenticated;

alter table public.household_invite_codes enable row level security;
alter table public.household_invitations enable row level security;
alter table private.invite_code_attempts enable row level security;

create policy "Inviters see invite codes" on public.household_invite_codes
    for select to authenticated
    using (private.has_permission(household_id, 'INVITE_MEMBERS'));

create policy "Inviters see invitations" on public.household_invitations
    for select to authenticated
    using (private.has_permission(household_id, 'INVITE_MEMBERS'));

revoke all on function private.random_invite_code() from public, anon, authenticated;

revoke execute on function
    public.create_invite_code(uuid, integer, integer),
    public.revoke_invite_code(uuid),
    public.join_household_with_code(text),
    public.create_invitation(uuid, text, public.household_role),
    public.revoke_invitation(uuid),
    public.my_pending_invitations(),
    public.respond_to_invitation(uuid, boolean)
from public, anon;

grant execute on function
    public.create_invite_code(uuid, integer, integer),
    public.revoke_invite_code(uuid),
    public.join_household_with_code(text),
    public.create_invitation(uuid, text, public.household_role),
    public.revoke_invitation(uuid),
    public.my_pending_invitations(),
    public.respond_to_invitation(uuid, boolean)
to authenticated, service_role;
