package app.betterhabits.ui.chores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.sync.SyncController
import app.betterhabits.data.sync.SyncStatus
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.schedule.ScheduleCalculator
import app.betterhabits.domain.schedule.ScheduledOccurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

data class ChoreListItem(val chore: Chore, val next: ScheduledOccurrence?)

data class ChoresUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val active: List<ChoreListItem> = emptyList(),
    val paused: List<ChoreListItem> = emptyList(),
    val refreshing: Boolean = false,
    val sync: SyncStatus = SyncStatus(),
    val message: AppError? = null,
) {
    val canCreate get() = context?.details?.iCan(HouseholdPermission.CREATE_CHORES) == true
    val canAssign get() = context?.details?.iCan(HouseholdPermission.ASSIGN_CHORES) == true
}

class ChoresViewModel(
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    session: HouseholdSession,
    private val sync: SyncController,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(ChoresUiState())
    val state: StateFlow<ChoresUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { sync.status.collect { s -> _state.update { it.copy(sync = s) } } }
        viewModelScope.launch {
            session.selectedHousehold().collectLatest { (householdId, userId) ->
                _state.update { it.copy(loading = true, context = null, active = emptyList(), paused = emptyList(), loadError = null) }
                val context = households.loadContext(householdId, userId).getOrElse { e ->
                    _state.update { it.copy(loading = false, loadError = e.appError) }
                    return@collectLatest
                }
                _state.update { it.copy(context = context) }
                chores.observeChores(householdId, context.zone).collect { list ->
                    // The current occurrence counts as "next" until the end of its due day.
                    val startOfToday = clock.instant().atZone(context.zone).toLocalDate().atStartOfDay(context.zone).toInstant()
                    val items = list.map { ChoreListItem(it, ScheduleCalculator.nextOccurrence(it.schedule, startOfToday.minusSeconds(1))) }
                        .sortedWith(compareBy(nullsLast()) { it.next?.dueAt })
                    _state.update { s ->
                        s.copy(
                            loading = false,
                            active = items.filter { it.chore.active },
                            paused = items.filterNot { it.chore.active },
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        val householdId = _state.value.context?.householdId ?: return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val error = sync.refresh(householdId).exceptionOrNull()?.appError
            _state.update { it.copy(refreshing = false, message = error?.takeUnless { e -> e == AppError.Network }) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun dismissSyncProblem(id: Long) = sync.dismissProblem(id)
}
