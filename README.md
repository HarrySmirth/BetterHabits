# BetterHabits

A household chore and habit app for Android. It shares chores out **fairly**, weighing estimated effort first and taking everyone's preferences into account.

Most chore apps ask *"who is assigned this?"*. BetterHabits aims to answer *"given everyone's preferences, availability, recent workload and the effort involved, who should do this?"* It also explains its reasoning.

## Features (planned; see status below)
- Shared households with roles (owner, admin, member, child), invite codes and email invites
- Chores with flexible recurring schedules, estimated time, checklists and completion history
- Smart, explainable chore allocation that balances workload against preferences
- Workload and fairness view (an estimate, not a claim of perfection)
- A chore template library
- Personal habits with streaks, including numeric and timed habits
- Reminders, a daily briefing, and "add to calendar"
- Works offline and syncs between household members
- Optional gamification (points, badges, rewards)

## Status
**Early development.** Phase 1 is implemented and tested: accounts, households, roles and permissions, invite codes, email invitations and child accounts. Chores arrive in Phase 2.

| Phase | Scope | Status |
|---|---|---|
| 0 | Project foundation, CI, release pipeline | ✅ |
| 1 | Auth, households, roles, invites, RLS | ✅ |
| 2 | Chores, recurrence, completion, history | ⏳ |
| 3 | Offline cache, sync, realtime | |
| 4 | Preferences, fairness, smart allocation | |
| 5 | Templates | |
| 6 | Habits and streaks | |
| 7 | Notifications and calendar | |
| 8 | Gamification | |
| 9 | Analytics and polish | |
| 10 | Release preparation | |

## Install
Signed APKs are published on the [Releases](../../releases) page once versions are tagged.
1. On your Android phone (Android 8.0+), download `BetterHabits-vX.Y.Z.apk` from the latest release.
2. Open it, and allow your browser or file manager to "install unknown apps" if asked.
3. Later releases install over the top as updates.

## Tech stack
Kotlin · Jetpack Compose · Material 3 · Navigation Compose · Coroutines/Flow · DataStore · Room · WorkManager · [Supabase](https://supabase.com) (Postgres, Auth, Row Level Security, Realtime) · Gradle 9 / AGP 9 · GitHub Actions.

## Architecture
```
:domain   Pure Kotlin business rules: scheduling, fairness, allocation, streaks. Fully unit-tested.
:app      Android UI (Compose + ViewModels) → domain → repositories → Room / Supabase
supabase/ SQL migrations, RLS policies and Edge Functions (version-controlled)
```
All permissions are enforced on the server by Postgres Row Level Security. The app holds only Supabase's client-safe publishable key. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/DATABASE.md](docs/DATABASE.md).

## Development
Quick start (full details in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)):
```sh
cp secrets.properties.example secrets.properties   # add your Supabase URL + publishable key
./gradlew :domain:test testDebugUnitTest            # unit tests
./gradlew lintDebug assembleDebug                   # lint + debug APK
./gradlew connectedDebugAndroidTest                 # UI tests on an emulator/device
```
You need Android Studio with SDK 37 and a full JDK 17+ for Gradle. Android Studio's bundled JBR works.

**Supabase:** create a project, then put its URL and publishable key in `secrets.properties`. Apply the migrations in `supabase/migrations/` with `npx supabase db push`. Never put the service-role key in this repo or the app.

**Testing:** domain logic (recurrence, fairness, allocation, streaks) has JVM unit tests. ViewModels are tested with fakes. Critical flows have Compose UI tests. CI runs unit tests, lint and a build on every push and PR.

**Releases:** pushing a tag `vX.Y.Z` builds, signs and publishes an APK. See [docs/RELEASES.md](docs/RELEASES.md).

## Contributing
This is a personal project, but issues and suggestions are welcome. Use conventional commit messages (`feat:`, `fix:` and so on). Keep business logic in `:domain`, with tests.
