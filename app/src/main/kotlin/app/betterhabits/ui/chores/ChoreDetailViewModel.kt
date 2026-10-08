package app.betterhabits.ui.chores

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreOccurrence
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.schedule.OccurrenceResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

data class ChoreDetailUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val chore: Chore? = null,
    val upcoming: List<ChoreOccurrence> = emptyList(),
    val history: List<OccurrenceRecord> = emptyList(),
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val message: AppError? = null,
    val closed: Boolean = false,
) {
    val canEdit get() = context?.details?.iCan(HouseholdPermission.EDIT_CHORES) == true
    val canDelete get() = context?.details?.iCan(HouseholdPermission.DELETE_CHORES) == true
}

class ChoreDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    /** Route arg (see ChoreDetailRoute). */
    private val choreId: String = requireNotNull(savedStateHandle.get<String>("choreId"))

    private val _state = MutableStateFlow(ChoreDetailUiState())
    val state: StateFlow<ChoreDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val (householdId, userId) = session.selectedHousehold().first()
            val result = households.loadContext(householdId, userId).mapCatching { context ->
                val chore = chores.chore(choreId, context.zone).getOrThrow()
                val now = clock.instant()
                val today = now.atZone(context.zone).toLocalDate()
                val records = chores.records(householdId, today, today.plusDays(UPCOMING_WINDOW_DAYS)).getOrThrow()
                val upcoming = OccurrenceResolver.resolve(listOf(chore), records, today, today.plusDays(UPCOMING_WINDOW_DAYS), now)
                    .filter { it.state == OccurrenceState.UPCOMING || it.state == OccurrenceState.SNOOZED }
                    .take(UPCOMING_COUNT)
                val history = chores.history(householdId, choreId, limit = HISTORY_LIMIT).getOrThrow()
                Triple(context, chore to upcoming, history)
            }
            result.onSuccess { (context, choreAndUpcoming, history) ->
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = null,
                        busy = false,
                        context = context,
                        chore = choreAndUpcoming.first,
                        upcoming = choreAndUpcoming.second,
                        history = history,
                    )
                }
            }.onFailure { e -> _state.update { it.copy(loading = false, busy = false, loadError = e.appError) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun setActive(active: Boolean) {
        val chore = _state.value.chore ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            chores.updateChore(chore.copy(active = active))
                .onSuccess { load() }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }

    fun delete() {
        _state.update { it.copy(busy = true, confirmDelete = false) }
        viewModelScope.launch {
            chores.deleteChore(choreId)
                .onSuccess { _state.update { it.copy(closed = true) } }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.appError) } }
        }
    }

    private companion object {
        const val UPCOMING_WINDOW_DAYS = 400L
        const val UPCOMING_COUNT = 5
        const val HISTORY_LIMIT = 50
    }
}
