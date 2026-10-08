package app.betterhabits.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdPermissions
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.HouseholdSummary
import app.betterhabits.domain.model.Invitation
import app.betterhabits.domain.model.InviteCode
import app.betterhabits.domain.model.Validation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

sealed interface HouseholdDialog {
    data object None : HouseholdDialog
    data class InviteByEmail(
        val email: String = "",
        val role: HouseholdRole = HouseholdRole.MEMBER,
        val submitting: Boolean = false,
        val error: AppError? = null,
        val showValidation: Boolean = false,
    ) : HouseholdDialog
    data class AddChild(
        val name: String = "",
        val pin: String = "",
        val submitting: Boolean = false,
        val error: AppError? = null,
        val showValidation: Boolean = false,
    ) : HouseholdDialog
    data class ChildCreated(val name: String, val username: String) : HouseholdDialog
    data object ConfirmLeave : HouseholdDialog
}

data class HouseholdUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val households: List<HouseholdSummary> = emptyList(),
    val details: HouseholdDetails? = null,
    val inviteCodes: List<InviteCode> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val now: Instant = Instant.EPOCH,
    val dialog: HouseholdDialog = HouseholdDialog.None,
    /** One-off error shown in a snackbar. */
    val message: AppError? = null,
    val refreshing: Boolean = false,
) {
    val canInvite get() = details?.iCan(HouseholdPermission.INVITE_MEMBERS) == true
    val canManageChildren get() = details?.iCan(HouseholdPermission.MANAGE_CHILDREN) == true
    val canOpenSettings get() = details?.me?.role.let { it == HouseholdRole.OWNER || it == HouseholdRole.ADMIN } ||
        details?.iCan(HouseholdPermission.MANAGE_SETTINGS) == true
    val canLeave get() = details?.let(HouseholdPermissions::canLeave) == true
    val canInviteAdmins get() = details?.me?.role == HouseholdRole.OWNER
    val activeCodes get() = inviteCodes.filter { it.isActive(now) }
}

class HouseholdViewModel(
    private val repository: HouseholdRepository,
    private val session: HouseholdSession,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val _state = MutableStateFlow(HouseholdUiState())
    val state: StateFlow<HouseholdUiState> = _state.asStateFlow()

    private var userId: String? = null
    private var householdId: String? = null

    init {
        viewModelScope.launch {
            session.state.filterIsInstance<SessionState.Ready>()
                .map { Triple(it.user.id, it.selected?.household?.id, it.households) }
                .distinctUntilChanged()
                .collect { (user, selected, households) ->
                    _state.update { it.copy(households = households) }
                    if (selected != null && (selected != householdId || user != userId)) {
                        userId = user
                        householdId = selected
                        _state.update { it.copy(loading = true, details = null, loadError = null) }
                        load()
                    }
                }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        session.refresh()
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val user = userId ?: return
        val id = householdId ?: return
        val result = coroutineScope {
            val details = async { repository.details(id, user) }
            val codes = async { repository.inviteCodes(id) }
            val invitations = async { repository.pendingInvitations(id) }
            Triple(details.await(), codes.await(), invitations.await())
        }
        val (details, codes, invitations) = result
        _state.update { s ->
            details.fold(
                onSuccess = {
                    s.copy(
                        loading = false,
                        refreshing = false,
                        loadError = null,
                        details = it,
                        inviteCodes = codes.getOrDefault(emptyList()),
                        invitations = invitations.getOrDefault(emptyList()),
                        now = clock.instant(),
                    )
                },
                onFailure = { e ->
                    if (s.details != null) {
                        s.copy(refreshing = false, message = e.appError)
                    } else {
                        s.copy(loading = false, refreshing = false, loadError = e.appError)
                    }
                },
            )
        }
    }

    fun selectHousehold(id: String) {
        viewModelScope.launch { session.select(id) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = HouseholdDialog.None) }

    // Invite codes

    fun createInviteCode() = action { id ->
        repository.createInviteCode(id, validHours = 72).onSuccess { code ->
            _state.update { it.copy(inviteCodes = listOf(code) + it.inviteCodes, now = clock.instant()) }
        }
    }

    fun revokeInviteCode(code: InviteCode) = action { _ ->
        repository.revokeInviteCode(code.id).onSuccess {
            _state.update { s -> s.copy(inviteCodes = s.inviteCodes.filterNot { it.id == code.id }) }
        }
    }

    // Email invitations

    fun showInviteByEmail() = _state.update { it.copy(dialog = HouseholdDialog.InviteByEmail()) }

    fun onInviteEmailChange(email: String) = updateDialog<HouseholdDialog.InviteByEmail> { it.copy(email = email, error = null) }

    fun onInviteRoleChange(role: HouseholdRole) = updateDialog<HouseholdDialog.InviteByEmail> { it.copy(role = role) }

    fun sendEmailInvite() {
        val dialog = _state.value.dialog as? HouseholdDialog.InviteByEmail ?: return
        val id = householdId ?: return
        if (!Validation.isValidEmail(dialog.email)) {
            updateDialog<HouseholdDialog.InviteByEmail> { it.copy(showValidation = true) }
            return
        }
        updateDialog<HouseholdDialog.InviteByEmail> { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            repository.inviteByEmail(id, dialog.email, dialog.role)
                .onSuccess { invitation ->
                    // The invitation exists now; the email is a best-effort notification.
                    val emailError = repository.sendInvitationEmail(invitation.id).exceptionOrNull()?.appError
                    _state.update { s ->
                        s.copy(
                            dialog = HouseholdDialog.None,
                            invitations = listOf(invitation) + s.invitations.filterNot { it.id == invitation.id },
                            message = emailError?.let { AppError.InviteEmailNotSent },
                        )
                    }
                }
                .onFailure { e -> updateDialog<HouseholdDialog.InviteByEmail> { it.copy(submitting = false, error = e.appError) } }
        }
    }

    fun revokeInvitation(invitation: Invitation) = action { _ ->
        repository.revokeInvitation(invitation.id).onSuccess {
            _state.update { s -> s.copy(invitations = s.invitations.filterNot { it.id == invitation.id }) }
        }
    }

    // Child accounts

    fun showAddChild() = _state.update { it.copy(dialog = HouseholdDialog.AddChild()) }

    fun onChildNameChange(name: String) = updateDialog<HouseholdDialog.AddChild> { it.copy(name = name, error = null) }

    fun onChildPinChange(pin: String) = updateDialog<HouseholdDialog.AddChild> { it.copy(pin = pin, error = null) }

    fun createChild() {
        val dialog = _state.value.dialog as? HouseholdDialog.AddChild ?: return
        val id = householdId ?: return
        if (!Validation.isValidDisplayName(dialog.name) || !Validation.isValidChildPin(dialog.pin)) {
            updateDialog<HouseholdDialog.AddChild> { it.copy(showValidation = true) }
            return
        }
        updateDialog<HouseholdDialog.AddChild> { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            repository.createChildAccount(id, dialog.name, dialog.pin)
                .onSuccess { credentials ->
                    _state.update { it.copy(dialog = HouseholdDialog.ChildCreated(dialog.name.trim(), credentials.username)) }
                    load()
                }
                .onFailure { e -> updateDialog<HouseholdDialog.AddChild> { it.copy(submitting = false, error = e.appError) } }
        }
    }

    // Leaving

    fun confirmLeave() = _state.update { it.copy(dialog = HouseholdDialog.ConfirmLeave) }

    fun leave() {
        val id = householdId ?: return
        _state.update { it.copy(dialog = HouseholdDialog.None) }
        viewModelScope.launch {
            repository.leave(id)
                .onSuccess {
                    householdId = null
                    session.refresh()
                }
                .onFailure { e -> _state.update { it.copy(message = e.appError) } }
        }
    }

    private inline fun <reified T : HouseholdDialog> updateDialog(crossinline transform: (T) -> T) =
        _state.update { s -> (s.dialog as? T)?.let { s.copy(dialog = transform(it)) } ?: s }

    private fun action(block: suspend (householdId: String) -> Result<*>) {
        val id = householdId ?: return
        viewModelScope.launch {
            block(id).onFailure { e -> _state.update { it.copy(message = e.appError) } }
        }
    }
}
