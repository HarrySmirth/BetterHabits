# Database (Supabase / Postgres)

> Status: the schema starts in Phase 1. This document records the design rules, and each phase adds its tables here.

## Rules
- Every change is a migration in `supabase/migrations/<timestamp>_<name>.sql`, including RLS policies, functions and grants. No undocumented dashboard edits.
- UUID primary keys (`gen_random_uuid()`). Timestamps are `timestamptz` in UTC. Each user's IANA timezone is stored on their profile, and each household's on the household.
- Every table has RLS **enabled** in the migration that creates it. Access is household-scoped through helper functions such as `is_household_member(household_id)` and `has_household_permission(household_id, permission)`.
- Synced tables carry `created_at`, `updated_at` (trigger-maintained) and soft-delete `deleted_at`, so offline clients can pull deletions.
- Recurring chores store a **rule**, not infinite rows. Occurrences are calculated from the rule, and a row is only created when something happens to an occurrence (completion, skip, snooze, reassignment).
- Privileged operations (creating child accounts, deleting accounts) run in Edge Functions using the service-role key from Supabase secrets, never from the client.

## Applying migrations
See docs/DEVELOPMENT.md → "Supabase".
