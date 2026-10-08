package app.betterhabits.ui.chores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.ChoreOccurrence
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

data class HistoryUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val entries: List<ChoreOccurrence> = emptyList(),
    /** Filter by member (as assignee or as the person who did it); null = everyone. */
    val person: String? = null,
) {
    val visible: List<ChoreOccurrence>
        get() = person?.let { p -> entries.filter { it.assigneeId == p || it.completedBy == p } } ?: entries
}

/** Completed, skipped and missed chores over the last [DAYS] days, updating live. */
class HistoryViewModel(
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

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
            val from = today.minusDays(DAYS)
            combine(chores.observeChores(householdId, context.zone), chores.observeRecords(householdId, from, today)) { list, records ->
                OccurrenceResolver.resolve(list, records, from, today, clock.instant())
                    .filter { it.state in HISTORY_STATES }
                    .sortedByDescending { it.record?.completedAt ?: it.dueAt }
            }.collect { entries -> _state.update { it.copy(loading = false, loadError = null, context = context, entries = entries) } }
        }
    }

    fun setPerson(userId: String?) = _state.update { it.copy(person = userId) }

    companion object {
        const val DAYS = 30L
        private val HISTORY_STATES = setOf(OccurrenceState.COMPLETED, OccurrenceState.SKIPPED, OccurrenceState.MISSED)
    }
}
