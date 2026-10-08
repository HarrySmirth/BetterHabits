package app.betterhabits.testing

import app.betterhabits.data.allocation.AllocationPlanner
import app.betterhabits.data.auth.AuthState
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.profile.ProfileRepository
import app.betterhabits.data.sync.SyncEngine
import app.betterhabits.di.AppContainer
import app.betterhabits.domain.model.Profile
import kotlinx.coroutines.CoroutineScope

class FakeProfileRepository(private val names: () -> Map<String, String>) : ProfileRepository {
    val timezones = mutableMapOf<String, String>()

    override suspend fun profile(userId: String) =
        Result.success(Profile(userId, names()[userId] ?: "Me", timezones[userId] ?: "UTC", isChild = false))

    override suspend fun updateDisplayName(userId: String, displayName: String) = Result.success(Unit)

    override suspend fun updateTimezone(userId: String, timezone: String): Result<Unit> {
        timezones[userId] = timezone
        return Result.success(Unit)
    }
}

/** Wires the real ViewModels and [HouseholdSession] to in-memory fakes. */
class FakeAppContainer(scope: CoroutineScope) : AppContainer {
    override val userPreferencesRepository = FakeUserPreferencesRepository()
    override val authRepository = FakeAuthRepository()
    override val householdRepository = FakeHouseholdRepository {
        (authRepository.authState.value as? AuthState.SignedIn)?.user?.id
    }
    override val profileRepository = FakeProfileRepository { householdRepository.names }
    override val choreRepository = FakeChoreRepository {
        (authRepository.authState.value as? AuthState.SignedIn)?.user?.id
    }
    override val householdSession = HouseholdSession(
        authRepository,
        householdRepository,
        profileRepository,
        userPreferencesRepository,
        scope,
    )
    override val allocationRepository = FakeAllocationRepository()
    override val allocationPlanner = AllocationPlanner(choreRepository, allocationRepository)
    override val templateRepository = FakeTemplateRepository()
    override val syncController = FakeSyncController()
    override val syncEngine: SyncEngine? = null

    override fun setForeground(foreground: Boolean) = Unit
}
