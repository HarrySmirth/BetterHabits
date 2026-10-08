package app.betterhabits.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.SessionState
import app.betterhabits.data.preferences.ThemeMode
import app.betterhabits.data.preferences.UserPreferences
import app.betterhabits.data.preferences.UserPreferencesRepository
import app.betterhabits.data.profile.ProfileRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Validation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProfileDialog {
    data object None : ProfileDialog
    data class EditName(val name: String, val submitting: Boolean = false, val error: AppError? = null) : ProfileDialog
    data object ConfirmSignOut : ProfileDialog
    data object ConfirmDelete : ProfileDialog
}

data class AccountUiState(
    val user: AuthUser? = null,
    val displayName: String? = null,
    val dialog: ProfileDialog = ProfileDialog.None,
    val deleting: Boolean = false,
    val message: AppError? = null,
)

class ProfileViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    sessionState: Flow<SessionState>,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _account = MutableStateFlow(AccountUiState())
    val account: StateFlow<AccountUiState> = _account.asStateFlow()

    init {
        viewModelScope.launch {
            sessionState.filterIsInstance<SessionState.Ready>().map { it.user }.distinctUntilChanged().collect { user ->
                _account.update { it.copy(user = user) }
                profileRepository.profile(user.id).onSuccess { p -> _account.update { it.copy(displayName = p.displayName) } }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setUseDynamicColor(enabled) }
    }

    fun showDialog(dialog: ProfileDialog) = _account.update { it.copy(dialog = dialog) }
    fun dismissDialog() = _account.update { it.copy(dialog = ProfileDialog.None) }
    fun messageShown() = _account.update { it.copy(message = null) }

    fun editName() = showDialog(ProfileDialog.EditName(_account.value.displayName.orEmpty()))

    fun onNameChange(name: String) = _account.update { s ->
        (s.dialog as? ProfileDialog.EditName)?.let { s.copy(dialog = it.copy(name = name, error = null)) } ?: s
    }

    fun saveName() {
        val dialog = _account.value.dialog as? ProfileDialog.EditName ?: return
        val user = _account.value.user ?: return
        if (!Validation.isValidDisplayName(dialog.name)) return
        _account.update { it.copy(dialog = dialog.copy(submitting = true)) }
        viewModelScope.launch {
            profileRepository.updateDisplayName(user.id, dialog.name)
                .onSuccess { _account.update { it.copy(displayName = dialog.name.trim(), dialog = ProfileDialog.None) } }
                .onFailure { e -> _account.update { it.copy(dialog = dialog.copy(submitting = false, error = e.appError)) } }
        }
    }

    fun signOut() {
        dismissDialog()
        viewModelScope.launch { authRepository.signOut() }
    }

    fun deleteAccount() {
        _account.update { it.copy(dialog = ProfileDialog.None, deleting = true) }
        viewModelScope.launch {
            authRepository.deleteAccount()
                .onFailure { e -> _account.update { it.copy(deleting = false, message = e.appError) } }
        }
    }
}
