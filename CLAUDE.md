# CLAUDE.md — BetterHabits

Android-first household chore and habit app. Its key feature is **fair, explainable chore allocation**: it weighs estimated effort first, then preferences, availability, history and rotation. Backend: Supabase. The full product spec lives in the original brief. The phase status is in README.md.

## Stack
Kotlin 2.4 · Jetpack Compose + Material 3 · Navigation Compose (type-safe routes) · ViewModel/StateFlow/Coroutines · DataStore · Room (Phase 3) · WorkManager · Supabase (Postgres, Auth, RLS, Realtime) · AGP 9.4 (built-in Kotlin, so **don't** apply `org.jetbrains.kotlin.android`) · Gradle 9.8.1 wrapper · version catalog `gradle/libs.versions.toml`.

## Architecture (details: docs/ARCHITECTURE.md)
- `:domain`: pure Kotlin/JVM. Scheduling, recurrence, fairness, allocation, streaks and versions live here. **No Android, Room or Supabase imports.** All business rules go here and are unit-tested here.
- `:app`: `ui/<feature>` (Screen + ViewModel) → domain → `data/` (repository interfaces + impls → local/remote sources). Composables hold no business logic.
- DI is manual: `di/AppContainer.kt`, and ViewModels are wired only in `ui/AppViewModelFactory.kt`. Don't add Hilt without a reason.
- Supabase DTOs stay in `data/`. Map them to domain models at the repository boundary.
- `supabase/migrations/`: every schema, RLS and function change is a timestamped migration. No manual dashboard changes.

## Commands (Windows: set `JAVA_HOME` to Android Studio's `jbr`; the PATH `java` is a JRE)
```
./gradlew :domain:test testDebugUnitTest   # unit tests (ViewModels use fakes in app/src/sharedTest)
./gradlew :supabase-tests:test            # migrations + RLS/RPC tests on embedded Postgres 17 (no Docker)
./gradlew lintDebug                        # lint (must be clean: 0 errors/warnings)
./gradlew assembleDebug assembleRelease    # builds
./gradlew connectedDebugAndroidTest        # UI tests; emulator AVD: Medium_Phone_API_37.0
```
Before claiming done: unit tests + lint + assembleDebug pass, and UI tests run when UI flows changed.

## Security rules (public repo)
- Never commit secrets. `secrets.properties` (Supabase URL + **publishable** key), `keystore.properties`, `*.jks`, `.env` and `local.properties` are all git-ignored.
- **Never** use the Supabase service-role/secret key in the app or repo. Privileged operations go in Edge Functions, with the key in Supabase function secrets.
- Permissions are enforced by RLS. Client-side checks are UX only. Every new table gets RLS enabled, plus policies, in the same migration.
- Child accounts are created by owners/admins via an Edge Function: an internal non-routable email plus a parent-set PIN. Children cannot self-register.
- Auth uses typed 6-digit email codes (OTP) for sign-up confirmation and password reset, not deep links.
- RPC errors: SQLSTATE 42501 = permission; P0001 + snake_case message key. Map new keys in data/remote/ErrorMapping.kt and give them friendly text in ui/components/Messages.kt.
- `HouseholdSession` (data/household) is the app-wide signed-in state (user, households, selected household); screens observe it.
- UI tests use `BetterHabitsTestRunner` + fakes; call `resetFakeContainer()` before launching MainActivity.

## Git conventions (user has authorised autonomous Git)
- Commit, push, branch, merge, tag and release without asking. Work on `main` for small changes and use short-lived branches for large phases.
- Conventional-style messages: `feat:`, `fix:`, `chore:`, `docs:`, `ci:`, `test:`, `refactor:`.
- **Never** force-push `main`, delete the repo, rewrite published history, or commit credentials or signing material.
- If CI fails after a push: read the logs, fix the root cause, test locally, and push again. Stop and ask only for external blockers (auth, secrets).

## Versioning & releases (details: docs/RELEASES.md)
- Version source of truth: `betterhabits.version` in `gradle.properties` (semver). `versionCode = MAJOR*10000 + MINOR*100 + PATCH`.
- Release: bump the version → commit → `git tag vX.Y.Z` → push the tag. `release.yml` tests, builds, signs and verifies, then publishes `BetterHabits-vX.Y.Z.apk` to GitHub Releases. The tag must equal the version, and an existing release is never overwritten.
- Signing key: production identity, never regenerated, never committed. CI gets it via the `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` secrets.

## CI
`.github/workflows/ci.yml` runs unit tests, lint and a debug build on pushes to `main` and on PRs. It needs no secrets. Builds without `secrets.properties` still compile, with empty Supabase values.
