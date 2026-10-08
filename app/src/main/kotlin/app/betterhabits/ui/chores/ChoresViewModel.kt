package app.betterhabits.ui.chores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.schedule.ScheduleCalculator
import app.betterhabits.domain.schedule.ScheduledOccurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val message: AppError? = null,
) {
    val canCreate get() = context?.details?.iCan(HouseholdPermission.CREATE_CHORES) == true
}

class ChoresViewModel(
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    session: HouseholdSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(ChoresUiState())
    val state: StateFlow<ChoresUiState> = _state.asStateFlow()
    private var selected: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            session.selectedHousehold().collect {
                selected = it
                _state.update { s -> s.copy(loading = true, context = null, active = emptyList(), paused = emptyList()) }
                load()
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { load() }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun load() {
        val (householdId, userId) = selected ?: return
        val result = households.loadContext(householdId, userId).mapCatching { context ->
            context to chores.chores(householdId, context.zone).getOrThrow()
        }
        result.onSuccess { (context, list) ->
            // The current occurrence counts as "next" until the end of its due day.
            val startOfToday = clock.instant().atZone(context.zone).toLocalDate().atStartOfDay(context.zone).toInstant()
            val items = list.map { ChoreListItem(it, ScheduleCalculator.nextOccurrence(it.schedule, startOfToday.minusSeconds(1))) }
                .sortedWith(compareBy(nullsLast()) { it.next?.dueAt })
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    loadError = null,
                    context = context,
                    active = items.filter { item -> item.chore.active },
                    paused = items.filterNot { item -> item.chore.active },
                )
            }
        }.onFailure { e ->
            _state.update { s ->
                if (s.context != null) s.copy(refreshing = false, message = e.appError)
                else s.copy(loading = false, refreshing = false, loadError = e.appError)
            }
        }
    }
}
