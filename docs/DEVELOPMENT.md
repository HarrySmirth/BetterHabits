# Development

## Requirements
- Android Studio (recent stable) with SDK Platform 37 installed.
- A **full JDK 17+** for Gradle. Android Studio's bundled `jbr` is easiest. For command-line builds:
  - Windows: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`
  - macOS: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Gradle is provided by the wrapper (`./gradlew`, 9.8.1, checksum-pinned).

## First-time setup
1. Clone, then open the folder in Android Studio. It creates `local.properties` with your `sdk.dir`.
2. Copy `secrets.properties.example` to `secrets.properties` and fill in the Supabase project URL and **publishable** key. Without it the app still builds, but backend features will not work.

## Commands
| Task | Command |
|---|---|
| Unit tests (domain + app JVM) | `./gradlew :domain:test testDebugUnitTest` |
| Lint | `./gradlew lintDebug` (kept at 0 issues) |
| Debug APK | `./gradlew assembleDebug` (installs as `app.betterhabits.debug`) |
| Release APK | `./gradlew assembleRelease` (signed only if `keystore.properties` exists) |
| UI tests | `./gradlew connectedDebugAndroidTest` (needs a running emulator/device) |

## Supabase
- Migrations live in `supabase/migrations/`. Apply them with the Supabase CLI (`npx supabase`):
  ```
  npx supabase login
  npx supabase link --project-ref <ref>
  npx supabase db push        # applies pending migrations to the linked project
  ```
- RLS tests live in `supabase/tests/` and run against a database you are allowed to reset. They are never run against production data.
- A local Supabase stack (`npx supabase start`) needs Docker.
