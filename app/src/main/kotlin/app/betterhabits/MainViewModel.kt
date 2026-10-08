package app.betterhabits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.data.preferences.UserPreferences
import app.betterhabits.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val preferences: UserPreferences, val session: SessionState) : MainUiState
}

class MainViewModel(
    preferencesRepository: UserPreferencesRepository,
    private val session: HouseholdSession,
    private val auth: AuthRepository,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = combine(preferencesRepository.preferences, session.state) { prefs, s ->
        MainUiState.Ready(prefs, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    fun retry() = session.refresh()

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
