package app.betterhabits.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.InviteCodes
import app.betterhabits.domain.model.PendingInvitation
import app.betterhabits.domain.model.Validation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class SetupAction { CREATE, JOIN, INVITATION }

data class HouseholdSetupUiState(
    val isChild: Boolean = false,
    val invitations: List<PendingInvitation> = emptyList(),
    val householdName: String = "",
    val code: String = "",
    val busy: SetupAction? = null,
    val createError: AppError? = null,
    val joinError: AppError? = null,
    val invitationError: AppError? = null,
    val showNameValidation: Boolean = false,
    /** Set once a household has been created/joined; the screen then closes or the app shell appears. */
    val completedHouseholdId: String? = null,
) {
    val codeWellFormed get() = InviteCodes.isWellFormed(InviteCodes.normalize(code))
}

class HouseholdSetupViewModel(
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HouseholdSetupUiState(isChild = (session.state.value as? SessionState.Ready)?.user?.isChild == true),
    )
    val state: StateFlow<HouseholdSetupUiState> = _state.asStateFlow()

    init {
        loadInvitations()
    }

    fun loadInvitations() {
        viewModelScope.launch {
            households.myPendingInvitations().onSuccess { list -> _state.update { it.copy(invitations = list) } }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(householdName = value, createError = null) }

    /** Keeps only valid characters and shows the code as XXXX-XXXX while typing. */
    fun onCodeChange(value: String) {
        val normalized = InviteCodes.normalize(value).take(InviteCodes.LENGTH)
        _state.update { it.copy(code = normalized, joinError = null) }
    }

    fun create() {
        val s = _state.value
        if (!Validation.isValidHouseholdName(s.householdName)) {
            _state.update { it.copy(showNameValidation = true) }
            return
        }
        _state.update { it.copy(busy = SetupAction.CREATE, createError = null) }
        viewModelScope.launch {
            households.createHousehold(s.householdName, ZoneId.systemDefault().id)
                .onSuccess { id -> complete(id) }
                .onFailure { e -> _state.update { it.copy(busy = null, createError = e.appError) } }
        }
    }

    fun join() {
        val s = _state.value
        if (!s.codeWellFormed) {
            _state.update { it.copy(joinError = AppError.InviteInvalid) }
            return
        }
        _state.update { it.copy(busy = SetupAction.JOIN, joinError = null) }
        viewModelScope.launch {
            households.joinWithCode(s.code)
                .onSuccess { result -> complete(result.householdId) }
                .onFailure { e -> _state.update { it.copy(busy = null, joinError = e.appError) } }
        }
    }

    fun respond(invitation: PendingInvitation, accept: Boolean) {
        _state.update { it.copy(busy = SetupAction.INVITATION, invitationError = null) }
        viewModelScope.launch {
            households.respondToInvitation(invitation.id, accept)
                .onSuccess { householdId ->
                    if (accept) {
                        complete(householdId)
                    } else {
                        _state.update { s -> s.copy(busy = null, invitations = s.invitations - invitation) }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = null, invitationError = e.appError) }
                    loadInvitations()
                }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    private suspend fun complete(householdId: String) {
        session.select(householdId)
        session.refresh()
        _state.update { it.copy(busy = null, completedHouseholdId = householdId) }
    }
}
