-- Phase 5: chore templates (household and personal), per-occurrence step progress for multi-step
-- chores, and template preferences. Built-in templates ship with the app as data and are not stored.

-- ---------------------------------------------------------------------------------------------
-- Templates
-- ---------------------------------------------------------------------------------------------

-- A template belongs to exactly one household (shared, managed with MANAGE_TEMPLATES) or one
-- person (personal, private to them and usable in any of their households).
create table public.chore_templates (
    id                uuid primary key default gen_random_uuid(),
    household_id      uuid references public.households (id) on delete cascade,
    owner_id          uuid references public.profiles (id) on delete cascade,
    name              text not null check (char_length(btrim(name)) between 1 and 80),
    description       text check (char_length(description) <= 1000),
    category          text not null default 'OTHER' check (category ~ '^[A-Z_]{1,40}$'),
    estimated_minutes integer not null check (estimated_minutes between 1 and 1440),
    difficulty        smallint not null default 3 check (difficulty between 1 and 5),
    points            integer not null default 0 check (points between 0 and 10000),
    -- Default schedule; the start date and weekday are chosen when the template is used.
    repeat_kind       text not null default 'WEEKLY' check (repeat_kind in ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    repeat_interval   smallint not null default 1 check (repeat_interval between 1 and 365),
    checklist         text[] not null default '{}' check (cardinality(checklist) <= 30),
    created_by        uuid references public.profiles (id) on delete set null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint chore_templates_one_scope check ((household_id is null) <> (owner_id is null))
);

create index chore_templates_household on public.chore_templates (household_id) where household_id is not null;
create index chore_templates_owner on public.chore_templates (owner_id) where owner_id is not null;

create trigger chore_templates_set_updated_at before update on public.chore_templates
    for each row execute function private.set_updated_at();

create function private.guard_template_write() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null or pg_trigger_depth() > 1 then
        return new;
    end if;
    if tg_op = 'INSERT' then
        new.created_by := auth.uid();
    elsif new.household_id is distinct from old.household_id or new.owner_id is distinct from old.owner_id
        or new.created_by is distinct from old.created_by or new.created_at <> old.created_at then
        -- A shared template can't be turned into someone's personal one (or moved), and vice versa.
        raise exception 'immutable_column' using errcode = '42501';
    end if;
    return new;
end;
$$;

create trigger chore_templates_guard_write before insert or update on public.chore_templates
    for each row execute function private.guard_template_write();

-- ---------------------------------------------------------------------------------------------
-- Chores remember their template; occurrences remember which steps are done
-- ---------------------------------------------------------------------------------------------

-- A household/personal template id, or a built-in key such as "builtin.kitchen.empty_dishwasher".
-- Not a foreign key: built-ins aren't rows, and deleting a template shouldn't touch its chores.
alter table public.chores
    add column template_id text check (char_length(template_id) between 1 and 64);

alter table public.chore_occurrences
    add column checked_steps smallint[] not null default '{}'
        check (cardinality(checked_steps) <= 30 and 0 <= all (checked_steps) and 30 > all (checked_steps));

-- ---------------------------------------------------------------------------------------------
-- Template preferences
-- ---------------------------------------------------------------------------------------------

alter table public.member_preferences
    drop constraint member_preferences_target_type_check,
    add constraint member_preferences_target_type_check check (target_type in ('CHORE', 'CATEGORY', 'TEMPLATE'));

-- ---------------------------------------------------------------------------------------------
-- Grants & RLS
-- ---------------------------------------------------------------------------------------------

revoke all on public.chore_templates from anon;
revoke all on all functions in schema private from public, anon;
grant execute on all functions in schema private to authenticated, service_role;

alter table public.chore_templates enable row level security;

create policy "Members see household templates, people see their own" on public.chore_templates
    for select to authenticated
    using (owner_id = (select auth.uid()) or (household_id is not null and private.is_member(household_id)));

create policy "Template managers and owners add templates" on public.chore_templates
    for insert to authenticated
    with check (owner_id = (select auth.uid())
                or (household_id is not null and private.has_permission(household_id, 'MANAGE_TEMPLATES')));

create policy "Template managers and owners change templates" on public.chore_templates
    for update to authenticated
    using (owner_id = (select auth.uid())
           or (household_id is not null and private.has_permission(household_id, 'MANAGE_TEMPLATES')))
    with check (owner_id = (select auth.uid())
                or (household_id is not null and private.has_permission(household_id, 'MANAGE_TEMPLATES')));

create policy "Template managers and owners delete templates" on public.chore_templates
    for delete to authenticated
    using (owner_id = (select auth.uid())
           or (household_id is not null and private.has_permission(household_id, 'MANAGE_TEMPLATES')));
