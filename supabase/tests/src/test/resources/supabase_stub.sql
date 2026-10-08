-- Minimal stand-in for the parts of a Supabase database our migrations rely on, so the real
-- migrations and RLS policies can be tested on a plain Postgres. Mirrors Supabase behaviour:
--  * roles anon / authenticated / service_role (service_role bypasses RLS)
--  * auth.users, auth.uid(), auth.jwt() reading the `request.jwt.claims` setting set by PostgREST
--  * default privileges that grant everything in `public` to the API roles (RLS is the guard)

create role anon nologin noinherit;
create role authenticated nologin noinherit;
create role service_role nologin noinherit bypassrls;

create schema auth;

create table auth.users (
    id                 uuid primary key default gen_random_uuid(),
    email              text,
    raw_user_meta_data jsonb not null default '{}'::jsonb,
    raw_app_meta_data  jsonb not null default '{}'::jsonb,
    created_at         timestamptz not null default now()
);

create function auth.jwt() returns jsonb language sql stable as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
$$;

create function auth.uid() returns uuid language sql stable as $$
    select nullif(auth.jwt() ->> 'sub', '')::uuid
$$;

grant usage on schema auth to anon, authenticated, service_role;
grant execute on all functions in schema auth to anon, authenticated, service_role;

grant usage on schema public to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
