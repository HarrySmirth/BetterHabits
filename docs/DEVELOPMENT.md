# Development

## Requirements
- Android Studio (recent stable) with SDK Platform 37 installed.
- A **full JDK 17+** for Gradle. Android Studio's bundled `jbr` is easiest. For command-line builds:
  - Windows: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`
  - macOS: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Gradle is provided by the wrapper (`./gradlew`, 9.8.1, checksum-pinned).
- Node.js, only for the Supabase CLI (`npx supabase`) and for type-checking Edge Functions (`npx deno check`).

## First-time setup
1. Clone, then open the folder in Android Studio. It creates `local.properties` with your `sdk.dir`.
2. Copy `secrets.properties.example` to `secrets.properties` and fill in the Supabase project URL and **publishable** key. You can also add the Google web client ID. Without these the app still builds, and shows a "Not connected" screen.

## Commands
| Task | Command |
|---|---|
| Domain + app unit tests | `./gradlew :domain:test testDebugUnitTest` |
| Database / RLS tests (embedded Postgres) | `./gradlew :supabase-tests:test` |
| Lint | `./gradlew lintDebug` (kept at 0 issues) |
| Debug APK | `./gradlew assembleDebug` (installs as `app.betterhabits.debug`) |
| Release APK | `./gradlew assembleRelease` (signed only if `keystore.properties` exists) |
| UI tests | `./gradlew connectedDebugAndroidTest` (needs a running emulator or device) |
| Type-check Edge Functions | `cd supabase/functions && npx deno check child-accounts/index.ts delete-account/index.ts` |

## Testing approach
- **`:domain`** holds pure business rules, tested directly.
- **App JVM tests** cover ViewModels and `HouseholdSession` against in-memory fakes in `app/src/sharedTest` (`FakeAuthRepository`, `FakeHouseholdRepository` and others).
- **UI tests** run the real app using `BetterHabitsTestRunner`, which starts `TestBetterHabitsApplication` with the same fakes. They never touch the network. Call `resetFakeContainer()` and seed data before `ActivityScenario.launch`.
- **`:supabase-tests`** runs the real migrations on Postgres 17 and checks RLS and RPC rules as different users. This is the authority on security behaviour.

## Supabase
See docs/DATABASE.md for the schema and the deploy commands (`db push`, `functions deploy`, `config push`).

Hosted-project settings that live outside the repo (dashboard):
- **Custom SMTP** (Authentication → Emails → SMTP Settings). Supabase's built-in mailer only delivers to project team members, so other people won't receive sign-up or password-reset codes without it.
- **Google provider** (Authentication → Providers → Google): Web client ID and secret. See docs/RELEASES.md and the README.

## Auth design notes
- Email confirmation and password reset use **6-digit codes** typed into the app (`{{ .Token }}` in `supabase/templates/`), not deep links, so the flow works even if the email is opened on another device.
- Child accounts are created by an owner or admin through the `child-accounts` Edge Function. They sign in with a generated username and a PIN set by the parent. The underlying email (`<username>@children.betterhabits.invalid`) is never routable or shown.
- Debug builds log backend failures with tag `BH`. Users only ever see the messages in `ui/components/Messages.kt`.
