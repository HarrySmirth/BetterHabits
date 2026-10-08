-- Phase 3: offline sync + realtime.
--
-- * Clients keep a local copy and pull rows changed since their last sync (updated_at cursors,
--   indexed in the chores migration). Realtime notifies them that something changed; RLS applies
--   to realtime too, so members only hear about their own households.
-- * Conflict rule for occurrences: the first completion wins. If two people complete the same
--   occurrence (e.g. both offline), the later write keeps the original completed_by/completed_at
--   instead of silently replacing who did it.

alter publication supabase_realtime add table public.chores, public.chore_occurrences;

create function private.keep_first_completion() returns trigger
language plpgsql set search_path = '' as $$
begin
    if old.status = 'COMPLETED' and new.status = 'COMPLETED' then
        new.completed_by := old.completed_by;
        new.completed_at := old.completed_at;
    end if;
    return new;
end;
$$;

-- Runs after chore_occurrences_guard_write (triggers fire in name order), so it sees the final values.
create trigger chore_occurrences_keep_first_completion before update on public.chore_occurrences
    for each row execute function private.keep_first_completion();

revoke all on function private.keep_first_completion() from public, anon;
