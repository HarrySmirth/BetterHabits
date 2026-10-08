package app.betterhabits.ui.household

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdMember
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdPermissions
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.Validation
import app.betterhabits.ui.navigation.MemberDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MemberDialog {
    data object None : MemberDialog
    data object ConfirmRemove : MemberDialog
    data object ConfirmTransfer : MemberDialog
    data object ConfirmDeleteChild : MemberDialog
    data class ResetPin(val pin: String = "", val submitting: Boolean = false, val error: AppError? = null) : MemberDialog
    data object PinReset : MemberDialog
}

data class MemberDetailUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val details: HouseholdDetails? = null,
    val member: HouseholdMember? = null,
    val dialog: MemberDialog = MemberDialog.None,
    val message: AppError? = null,
    val busy: Boolean = false,
    /** The member is no longer in the household (removed / deleted): close the screen. */
    val closed: Boolean = false,
) {
    private val me get() = details?.me
    val assignableRoles: List<HouseholdRole>
        get() = member?.let { m -> me?.let { HouseholdPermissions.assignableRoles(it.role, m) } }.orEmpty()
    val canEditPermissions: Boolean
        get() = member?.let { m -> me?.let { HouseholdPermissions.canManageMember(it.role, m.role) } } == true
    val canRemove get() = details != null && member != null && HouseholdPermissions.canRemove(details, member)
    val canTransfer get() = details != null && member != null && HouseholdPermissions.canTransferOwnershipTo(details, member)
    val canManageChild get() = details != null && member != null && HouseholdPermissions.canManageChildAccount(details, member)
    val overrides: Map<HouseholdPermission, Boolean> get() = member?.let { details?.overridesFor(it.userId) }.orEmpty()
    fun roleDefault(permission: HouseholdPermission): Boolean =
        member?.role == HouseholdRole.OWNER || permission in details?.rolePermissions?.get(member?.role).orEmpty()
}

class MemberDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: HouseholdRepository,
    private val session: HouseholdSession,
) : ViewModel() {

    // Route args are stored in SavedStateHandle by property name (see MemberDetailRoute).
    private val route = MemberDetailRoute(
        householdId = requireNotNull(savedStateHandle.get<String>("householdId")),
        userId = requireNotNull(savedStateHandle.get<String>("userId")),
    )
    private val _state = MutableStateFlow(MemberDetailUiState())
    val state: StateFlow<MemberDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val userId = (session.state.value as? SessionState.Ready)?.user?.id ?: return
        viewModelScope.launch {
            repository.details(route.householdId, userId)
                .onSuccess { details ->
                    val member = details.member(route.userId)
                    _state.update {
                        it.copy(loading = false, loadError = null, busy = false, details = details, member = member, closed = member == null)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, busy = false, loadError = e.appError) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun dismissDialog() = _state.update { it.copy(dialog = MemberDialog.None) }
    fun showDialog(dialog: MemberDialog) = _state.update { it.copy(dialog = dialog) }

    fun changeRole(role: HouseholdRole) = mutate { repository.changeRole(route.householdId, route.userId, role) }

    /** [granted] null = follow the role default. */
    fun setOverride(permission: HouseholdPermission, granted: Boolean?) =
        mutate { repository.setMemberOverride(route.householdId, route.userId, permission, granted) }

    fun remove() = mutate(closeOnSuccess = true) { repository.removeMember(route.householdId, route.userId) }

    fun transferOwnership() = mutate {
        repository.transferOwnership(route.householdId, route.userId).onSuccess { session.refresh() }
    }

    fun deleteChild() = mutate(closeOnSuccess = true) { repository.deleteChildAccount(route.userId) }

    fun onPinChange(pin: String) = _state.update { s ->
        (s.dialog as? MemberDialog.ResetPin)?.let { s.copy(dialog = it.copy(pin = pin, error = null)) } ?: s
    }

    fun resetPin() {
        val dialog = _state.value.dialog as? MemberDialog.ResetPin ?: return
        if (!Validation.isValidChildPin(dialog.pin)) {
            _state.update { it.copy(dialog = dialog.copy(error = AppError.WeakPassword)) }
            return
        }
        _state.update { it.copy(dialog = dialog.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            repository.resetChildPin(route.userId, dialog.pin)
                .onSuccess { _state.update { it.copy(dialog = MemberDialog.PinReset) } }
                .onFailure { e -> _state.update { it.copy(dialog = dialog.copy(submitting = false, error = e.appError)) } }
        }
    }

    private fun mutate(closeOnSuccess: Boolean = false, block: suspend () -> Result<*>) {
        _state.update { it.copy(busy = true, dialog = MemberDialog.None) }
        viewModelScope.launch {
            block()
                .onSuccess {
                    if (closeOnSuccess) {
                        session.refresh()
                        _state.update { it.copy(busy = false, closed = true) }
                    } else {
                        load()
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }
}
