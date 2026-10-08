package app.betterhabits.data.household

import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.AuthState
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.preferences.UserPreferencesRepository
import app.betterhabits.data.profile.ProfileRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.HouseholdSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

sealed interface SessionState {
    data object Loading : SessionState
    data object NotConfigured : SessionState
    data object SignedOut : SessionState

    /** Signed in, but the household list couldn't be loaded and nothing is cached yet. */
    data class Failed(val user: AuthUser, val error: AppError) : SessionState

    data class Ready(
        val user: AuthUser,
        val households: List<HouseholdSummary>,
        val selected: HouseholdSummary?,
    ) : SessionState
}

/**
 * App-wide signed-in context: who is signed in, which households they belong to and which one
 * is selected. Screens observe this instead of each re-deriving it.
 */
class HouseholdSession(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    private val profileRepository: ProfileRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val scope: CoroutineScope,
) {
    private sealed interface Loaded {
        data object Loading : Loaded
        data object NotConfigured : Loaded
        data object SignedOut : Loaded
        data class Failed(val user: AuthUser, val error: AppError) : Loaded
        data class Households(val user: AuthUser, val list: List<HouseholdSummary>) : Loaded
    }

    private val loaded = MutableStateFlow<Loaded>(Loaded.Loading)
    private var loadJob: Job? = null

    val state: StateFlow<SessionState> = combine(
        loaded,
        preferencesRepository.preferences.map { it.selectedHouseholdId }.distinctUntilChanged(),
    ) { loaded, selectedId ->
        when (loaded) {
            Loaded.Loading -> SessionState.Loading
            Loaded.NotConfigured -> SessionState.NotConfigured
            Loaded.SignedOut -> SessionState.SignedOut
            is Loaded.Failed -> SessionState.Failed(loaded.user, loaded.error)
            is Loaded.Households -> SessionState.Ready(
                user = loaded.user,
                households = loaded.list,
                selected = loaded.list.firstOrNull { it.household.id == selectedId } ?: loaded.list.firstOrNull(),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        scope.launch {
            authRepository.authState.collect { auth ->
                when (auth) {
                    AuthState.Loading -> loaded.value = Loaded.Loading
                    AuthState.NotConfigured -> loaded.value = Loaded.NotConfigured
                    AuthState.SignedOut -> {
                        loadJob?.cancel()
                        loaded.value = Loaded.SignedOut
                    }
                    is AuthState.SignedIn -> {
                        val current = loaded.value
                        val sameUser = current is Loaded.Households && current.user.id == auth.user.id
                        if (!sameUser) {
                            loaded.value = Loaded.Loading
                            load(auth.user)
                            syncTimezone(auth.user)
                        }
                    }
                }
            }
        }
    }

    /** Reloads the household list, keeping the current list visible while it loads. */
    fun refresh() {
        val user = when (val current = loaded.value) {
            is Loaded.Households -> current.user
            is Loaded.Failed -> current.user
            else -> return
        }
        load(user)
    }

    suspend fun select(householdId: String) = preferencesRepository.setSelectedHouseholdId(householdId)

    private fun load(user: AuthUser) {
        loadJob?.cancel()
        loadJob = scope.launch {
            householdRepository.myHouseholds(user.id)
                .onSuccess { loaded.value = Loaded.Households(user, it) }
                .onFailure { error ->
                    // Keep showing the last good list on a transient failure.
                    if (loaded.value !is Loaded.Households) loaded.value = Loaded.Failed(user, error.appError)
                }
        }
    }

    private fun syncTimezone(user: AuthUser) {
        scope.launch {
            val deviceZone = ZoneId.systemDefault().id
            val profile = profileRepository.profile(user.id).getOrNull() ?: return@launch
            if (profile.timezone != deviceZone) profileRepository.updateTimezone(user.id, deviceZone)
        }
    }
}
