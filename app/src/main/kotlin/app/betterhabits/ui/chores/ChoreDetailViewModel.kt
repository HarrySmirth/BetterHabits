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
import kotlinx.coroutines.flow.combine
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
    val confirmDelete: Boolean = false,
    val message: AppError? = null,
    /** Deleted (here or on another phone) or never existed: close the screen. */
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
            val context = households.loadContext(householdId, userId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            val today = clock.instant().atZone(context.zone).toLocalDate()
            combine(
                chores.observeChore(choreId, context.zone),
                chores.observeRecords(householdId, today, today.plusDays(UPCOMING_WINDOW_DAYS)),
                chores.observeHistory(householdId, choreId, HISTORY_LIMIT),
            ) { chore, records, history ->
                val upcoming = chore?.let {
                    OccurrenceResolver.resolve(listOf(it), records, today, today.plusDays(UPCOMING_WINDOW_DAYS), clock.instant())
                        .filter { o -> o.state == OccurrenceState.UPCOMING || o.state == OccurrenceState.SNOOZED }
                        .take(UPCOMING_COUNT)
                }.orEmpty()
                Triple(chore, upcoming, history)
            }.collect { (chore, upcoming, history) ->
                _state.update {
                    if (chore == null) it.copy(loading = false, closed = true)
                    else it.copy(loading = false, context = context, chore = chore, upcoming = upcoming, history = history)
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun setActive(active: Boolean) {
        val chore = _state.value.chore ?: return
        viewModelScope.launch {
            chores.updateChore(chore, chore.copy(active = active)).onFailure { e -> _state.update { it.copy(message = e.appError) } }
        }
    }

    fun delete() {
        val chore = _state.value.chore ?: return
        _state.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            chores.deleteChore(chore.householdId, chore.id).onFailure { e -> _state.update { it.copy(message = e.appError) } }
        }
    }

    private companion object {
        const val UPCOMING_WINDOW_DAYS = 400L
        const val UPCOMING_COUNT = 5
        const val HISTORY_LIMIT = 50
    }
}
