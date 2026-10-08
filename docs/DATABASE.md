# Database (Supabase / Postgres 17)

## Rules
- Every change is a migration in `supabase/migrations/<timestamp>_<name>.sql`, including RLS policies, functions and grants. No undocumented dashboard edits.
- UUID primary keys (`gen_random_uuid()`). Timestamps are `timestamptz` in UTC. IANA timezones are stored on profiles and households and validated against `pg_timezone_names`.
- Every table has RLS **enabled** in the migration that creates it. `anon` gets no access to app tables.
- RLS helper functions live in the **`private`** schema, which PostgREST doesn't expose. They are `SECURITY DEFINER`, so policies on `household_members` don't recurse.
- Business-rule operations are `SECURITY DEFINER` RPCs in `public`, with `set search_path = ''`. Clients cannot write `household_members` directly.
- RPC errors use SQLSTATE `42501` for permission failures (HTTP 403) or `P0001` with a stable snake_case message key (for example `transfer_ownership_required`). The app maps these to friendly text in `data/remote/ErrorMapping.kt`.
- Privileged operations that need the service-role key (creating, resetting and deleting child accounts; deleting your own account) are Edge Functions in `supabase/functions/`. They verify the caller's JWT and permissions first.
- Recurring chores (Phase 2) store a rule, not an infinite set of rows. Rows are only created when something happens to an occurrence.

## Phase 1 schema

| Table | Purpose | Access (RLS) |
|---|---|---|
| `profiles` | 1:1 with `auth.users`, created by trigger. `display_name`, `timezone`, `is_child` (set only from server-controlled `app_metadata`). | Read: yourself and household mates. Update: your own `display_name` and `timezone` only (column grants). |
| `households` | `name`, `timezone`, `created_by` | Read: members. Update name/timezone: `MANAGE_SETTINGS`. Delete: owner. Create: `create_household()` RPC. |
| `household_members` | PK `(household_id, user_id)`, `role` ∈ OWNER/ADMIN/MEMBER/CHILD. A unique partial index enforces one OWNER. | Read: fellow members. Writes go through RPCs only. |
| `household_role_permissions` | Rows mean the role has that permission in this household. Seeded with defaults on creation. | Read: members. Insert/delete: owner (any role except OWNER) or admin (MEMBER/CHILD roles). |
| `household_member_permission_overrides` | Per-member allow/deny that beats the role default. | Read: the member themselves, plus owners and admins. Write: owners for anyone but themselves; admins for members and children. |
| `household_invite_codes` | 8-character code from the alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (about 40 bits), with `expires_at`, `max_uses`, `use_count` and `revoked_at`. | Read: `INVITE_MEMBERS`. Create/revoke: RPCs. |
| `household_invitations` | Invitation addressed to an email, with status PENDING/ACCEPTED/DECLINED/REVOKED and a 14-day expiry. | Read: `INVITE_MEMBERS`. The invitee sees their invitations via `my_pending_invitations()`. |
| `private.invite_code_attempts` | Per-user log of attempts to redeem a code. | Not accessible to clients. |

**Permissions** (`household_permission` enum): CREATE_CHORES, EDIT_CHORES, DELETE_CHORES, ASSIGN_CHORES, MANAGE_TEMPLATES, MANAGE_SETTINGS, INVITE_MEMBERS, REMOVE_MEMBERS, MANAGE_CHILDREN, CONFIGURE_ALLOCATION.
- The OWNER always has every permission.
- ADMINs get all permissions by default.
- MEMBERs get the chore permissions, templates and invites by default.
- CHILDREN get no permissions by default.
- Owners and admins can change these per household and per member.
- Add a new permission with `alter type ... add value`. Older clients ignore values they don't recognise.

### RPCs
| Function | Rules |
|---|---|
| `create_household(name, tz)` | Children can't create households. The caller becomes OWNER, and default permissions are seeded. |
| `join_household_with_code(code)` | Returns `{status: joined, already_member, invalid, expired or rate_limited}`. Formatting in the code is ignored. 10 failed attempts per user per hour triggers rate limiting. An invalid code reveals nothing. Children can't use codes. |
| `create_invite_code(household, hours, max_uses)` / `revoke_invite_code(id)` | Require `INVITE_MEMBERS`. Codes last 1 to 720 hours. |
| `create_invitation(household, email, role)` / `revoke_invitation(id)` | Require `INVITE_MEMBERS`. Only the owner can invite admins. Re-inviting the same email refreshes the pending invitation. |
| `my_pending_invitations()` / `respond_to_invitation(id, accept)` | Matched on the caller's JWT email. |
| `leave_household(id)` | Children can't leave. An owner must transfer ownership first, unless they are the only member, in which case the household is deleted. |
| `remove_household_member`, `change_member_role` | Owners manage anyone but themselves. Admins manage members and children. Child accounts always keep the CHILD role. |
| `transfer_household_ownership(id, new_owner)` | Owner only. The new owner can't be a child account. The old owner becomes ADMIN. |
| `prepare_account_deletion()` | Used by the `delete-account` function. It deletes households where you're the only member, and refuses if you own a shared household. |
| `has_household_permission(id, perm)` | Reports only the caller's own permissions. |

### Deletion and cascades
- Deleting an auth user cascades to their profile, and from there to their memberships.
- The trigger `guard_owner_removal` blocks removing an OWNER membership while other members remain, so a household can never be left without an owner.
- The trigger `delete_empty_household` deletes a household when its last member goes.
- Deleting a household cascades to its members, permissions, codes and invitations.

## Testing (`:supabase-tests`)
`./gradlew :supabase-tests:test` starts an embedded **Postgres 17**. It loads `supabase/tests/src/test/resources/supabase_stub.sql`, which provides the roles, `auth.users`, `auth.uid()`/`auth.jwt()` and default grants a Supabase database has. It then applies every migration in order and runs JUnit tests as different users with real RLS. No Docker is needed, and it runs in CI.

## Applying migrations to the hosted project
```sh
npx supabase login                                  # once, opens a browser
npx supabase link --project-ref <your-project-ref>
npx supabase db push                                # applies pending migrations
npx supabase functions deploy child-accounts delete-account send-invitation
npx supabase secrets set RESEND_API_KEY=re_...         # send-invitation emails (Resend API key, never committed)
npx supabase config push                            # auth settings + email templates from config.toml
```
`config push` shows a diff and asks per service. Check the diff first. SMTP and Google provider credentials are set in the dashboard and are deliberately not declared in `config.toml`, so the CLI leaves them unchanged. Keep `otp_length = 6`, because the app expects 6-digit codes.
