# Architecture

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:domain` | Kotlin/JVM | Domain models and pure business rules: recurrence/scheduling, workload & fairness, allocation engine, habit streaks, version comparison. Deterministic and unit-tested. |
| `:app` | Android application | UI (Compose), ViewModels, repositories, Supabase and Room data sources, WorkManager, notifications and calendar. |

`:domain` has no Android, Room or Supabase dependencies. That keeps the core rules fast to test and portable, so a future iOS or web client could share them through KMP or reimplement them against the same tests.

## Layers inside `:app`

```
ui/<feature>/XxxScreen.kt     Stateless-ish composables: render state, forward events
ui/<feature>/XxxViewModel.kt  Holds StateFlow<UiState>, calls domain/repositories
        │
domain (module)               Use cases / domain services, pure functions
        │
data/<area>/XxxRepository     Interface (consumed by ViewModels) + implementation
data/<area>/remote|local      Supabase DTOs / Room entities + mappers
```

- **Dependency injection:** done by hand in `di/AppContainer.kt`. All ViewModels are constructed in `ui/AppViewModelFactory.kt`.
- **State:** each screen exposes one `UiState` (loading / content / error). One-off events are modelled as state, not callbacks from the ViewModel.
- **Navigation:** type-safe `@Serializable` routes. There are five top-level tabs (Today, Chores, Habits, Household, Profile). Household-scoped screens nest under Household.

## Offline-first (Phase 3)

Room is the source of truth for UI. Local writes go to Room and also into an outbox. A WorkManager sync worker pushes the outbox to Supabase and pulls changes using `updated_at` cursors, and Realtime triggers incremental pulls. Conflicts are settled by the server using row versions. See docs/DATABASE.md.

## Allocation engine (Phase 4)

This is a deterministic scoring model in `:domain`, not a black box. Workload (estimated minutes) is the main term; preference, availability, rotation and undesirable-chore balance adjust it. Every decision carries its list of reasons, so the UI can explain it. Manual locks and exclusions are hard constraints.

## Key decisions log

| Decision | Why |
|---|---|
| Manual DI instead of Hilt | The graph is small; avoids annotation processing and build time. |
| Pure `:domain` module | Testability and portability of the core logic. |
| java.time with minSdk 26 | Timezone-aware scheduling without extra libraries. |
| App backups disabled | Server is the source of truth; restored sessions or queues would be stale or unsafe. |
| Supabase publishable key in BuildConfig | Client-safe by design; security comes from RLS. Kept out of git for hygiene. |
| Child accounts via an Edge Function | Supabase Auth requires an identifier. An admin-created account (internal email + parent-set PIN) keeps children email-free without shared passwords. |
