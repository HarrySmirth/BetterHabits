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
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdPermissions
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.Validation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HouseholdSettingsUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val details: HouseholdDetails? = null,
    val name: String = "",
    val busy: Boolean = false,
    val message: AppError? = null,
    val confirmDelete: Boolean = false,
    val closed: Boolean = false,
) {
    val canRename get() = details?.iCan(HouseholdPermission.MANAGE_SETTINGS) == true
    val nameChanged get() = details != null && name.trim() != details.household.name && Validation.isValidHouseholdName(name)
    val canDelete get() = details?.me?.role == HouseholdRole.OWNER
    fun canEditRole(role: HouseholdRole) = details?.me?.role?.let { HouseholdPermissions.canManageRole(it, role) } == true
    fun roleHas(role: HouseholdRole, permission: HouseholdPermission) = permission in details?.rolePermissions?.get(role).orEmpty()
}

class HouseholdSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: HouseholdRepository,
    private val session: HouseholdSession,
) : ViewModel() {

    // Route args are stored in SavedStateHandle by property name (see HouseholdSettingsRoute).
    private val householdId: String = requireNotNull(savedStateHandle.get<String>("householdId"))
    private val _state = MutableStateFlow(HouseholdSettingsUiState())
    val state: StateFlow<HouseholdSettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val userId = (session.state.value as? SessionState.Ready)?.user?.id ?: return
        viewModelScope.launch {
            repository.details(householdId, userId)
                .onSuccess { d ->
                    _state.update { s ->
                        s.copy(
                            loading = false,
                            loadError = null,
                            busy = false,
                            details = d,
                            name = if (s.details == null) d.household.name else s.name,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, busy = false, loadError = e.appError) } }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun saveName() {
        val s = _state.value
        if (!s.nameChanged) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.renameHousehold(householdId, s.name)
                .onSuccess {
                    _state.update { it.copy(details = null) }
                    session.refresh()
                    load()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }

    fun setRolePermission(role: HouseholdRole, permission: HouseholdPermission, granted: Boolean) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.setRolePermission(householdId, role, permission, granted)
                .onSuccess { load() }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun delete() {
        _state.update { it.copy(busy = true, confirmDelete = false) }
        viewModelScope.launch {
            repository.deleteHousehold(householdId)
                .onSuccess {
                    session.refresh()
                    _state.update { it.copy(closed = true) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
