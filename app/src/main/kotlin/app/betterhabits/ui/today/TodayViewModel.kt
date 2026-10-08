package app.betterhabits.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.allocation.AllocationPlanner
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.sync.SyncController
import app.betterhabits.data.sync.SyncStatus
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.ChoreOccurrence
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.model.sum
import app.betterhabits.domain.schedule.OccurrenceResolver
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.loadContext
import app.betterhabits.ui.chores.selectedHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class AgendaFilter { MINE, EVERYONE }

enum class SnoozeOption { ONE_HOUR, TOMORROW_MORNING }

/** Something the user just did that can be undone from the snackbar. */
data class UndoableAction(val occurrence: ChoreOccurrence, val previous: OccurrenceRecord?, val completed: Boolean)

data class TodayUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val agenda: List<ChoreOccurrence> = emptyList(),
    val filter: AgendaFilter = AgendaFilter.MINE,
    val today: LocalDate = LocalDate.MIN,
    val refreshing: Boolean = false,
    val sync: SyncStatus = SyncStatus(),
    val message: AppError? = null,
    val undo: UndoableAction? = null,
) {
    val visible: List<ChoreOccurrence>
        get() = if (filter == AgendaFilter.EVERYONE) agenda else agenda.filter { it.assigneeId == context?.myId }
    val overdue get() = visible.filter { it.state == OccurrenceState.OVERDUE }
    val dueToday get() = visible.filter { it.state == OccurrenceState.UPCOMING }
    val done get() = visible.filter { it.isDone }
    val doneCount get() = done.size
    val totalCount get() = visible.size
    /** Effort is the fairness measure, so progress also shows the estimated time left. */
    val remainingEffort get() = (overdue + dueToday).map { it.chore.effort }.sum()
    val canCreate get() = context?.details?.iCan(HouseholdPermission.CREATE_CHORES) == true

    /** Children can only tick off their own chores (the server enforces this too). */
    fun canAct(occurrence: ChoreOccurrence): Boolean =
        context?.let { !it.isChild || occurrence.assigneeId == it.myId } == true
}

class TodayViewModel(
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    session: HouseholdSession,
    private val sync: SyncController,
    private val planner: AllocationPlanner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** Local writes commit in tap order, so the outbox (and then the server) sees them in that order. */
    private val writes = Mutex()

    init {
        viewModelScope.launch { sync.status.collect { s -> _state.update { it.copy(sync = s) } } }
        viewModelScope.launch {
            session.selectedHousehold().collectLatest { (householdId, userId) ->
                _state.update { it.copy(loading = true, context = null, agenda = emptyList(), loadError = null) }
                val context = households.loadContext(householdId, userId).getOrElse { e ->
                    _state.update { it.copy(loading = false, loadError = e.appError) }
                    return@collectLatest
                }
                val today = clock.instant().atZone(context.zone).toLocalDate()
                _state.update { s ->
                    s.copy(context = context, today = today, filter = if (context.isChild) AgendaFilter.MINE else s.filter)
                }
                // Live: re-resolves whenever the local copy changes (own taps, sync, other phones).
                combine(
                    chores.observeChores(householdId, context.zone),
                    chores.observeRecords(householdId, today.minusDays(LOOKBACK_DAYS), today),
                ) { list, records -> OccurrenceResolver.agenda(list, records, today, clock.instant(), LOOKBACK_DAYS) }
                    .collect { agenda -> _state.update { it.copy(loading = false, agenda = agenda) } }
            }
        }
    }

    /** Pull to refresh: push queued changes and fetch the latest. Being offline isn't an error here. */
    fun refresh() {
        val householdId = _state.value.context?.householdId ?: return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val error = sync.refresh(householdId).exceptionOrNull()?.appError
            _state.update { it.copy(refreshing = false, message = error?.takeUnless { e -> e == AppError.Network }) }
        }
    }

    fun setFilter(filter: AgendaFilter) = _state.update { it.copy(filter = filter) }
    fun messageShown() = _state.update { it.copy(message = null) }
    fun undoShown() = _state.update { it.copy(undo = null) }
    fun dismissSyncProblem(id: Long) = sync.dismissProblem(id)

    /**
     * The checkbox: ticked means completed. Unticking a completed occurrence makes it pending again;
     * ticking anything else (pending, overdue or skipped) completes it.
     */
    fun toggle(occurrence: ChoreOccurrence) {
        if (occurrence.state == OccurrenceState.COMPLETED) {
            reset(occurrence)
        } else {
            val now = clock.instant()
            act(undo = UndoableAction(occurrence, occurrence.record, completed = true)) {
                chores.complete(it, occurrence.chore.id, occurrence.key, now).onSuccess { rotateIfNeeded(occurrence) }
            }
        }
    }

    /** Ticks or unticks one step of a multi-step chore. Ticking the last open step completes it. */
    fun toggleStep(occurrence: ChoreOccurrence, index: Int) {
        if (index !in occurrence.chore.checklist.indices) return
        val current = occurrence.checkedSteps
        val steps = if (index in current) current - index else current + index
        val finishes = occurrence.state != OccurrenceState.COMPLETED && steps.size == occurrence.chore.checklist.size
        val now = clock.instant()
        act(undo = if (finishes) UndoableAction(occurrence, occurrence.record, completed = true) else null) { householdId ->
            chores.setCheckedSteps(householdId, occurrence.chore.id, occurrence.key, steps).mapCatching {
                if (finishes) {
                    chores.complete(householdId, occurrence.chore.id, occurrence.key, now).getOrThrow()
                    rotateIfNeeded(occurrence)
                }
            }
        }
    }

    /** "Take turns" chores move to the next person once done (if this user may assign chores). */
    private suspend fun rotateIfNeeded(occurrence: ChoreOccurrence) {
        val context = _state.value.context ?: return
        if (!occurrence.chore.rotate || !context.details.iCan(HouseholdPermission.ASSIGN_CHORES)) return
        planner.rotateAfterCompletion(context.details, context.zone, occurrence.chore.id)
    }

    /** Back to pending, e.g. "Undo" on a skipped occurrence. */
    fun reset(occurrence: ChoreOccurrence) = act { chores.reset(it, occurrence.chore.id, occurrence.key) }

    fun skip(occurrence: ChoreOccurrence) =
        act(undo = UndoableAction(occurrence, occurrence.record, completed = false)) {
            chores.skip(it, occurrence.chore.id, occurrence.key)
        }

    fun snooze(occurrence: ChoreOccurrence, option: SnoozeOption) {
        val now = clock.instant()
        val until: Instant = when (option) {
            SnoozeOption.ONE_HOUR -> now.plus(Duration.ofHours(1))
            SnoozeOption.TOMORROW_MORNING ->
                now.atZone(clock.zone).toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(clock.zone).toInstant()
        }
        act(undo = UndoableAction(occurrence, occurrence.record, completed = false)) {
            chores.snooze(it, occurrence.chore.id, occurrence.key, until)
        }
    }

    /** Restores the occurrence to how it was before [action]. */
    fun undo(action: UndoableAction) {
        val occurrence = action.occurrence
        val previous = action.previous
        act { householdId ->
            when {
                previous == null || previous.status == OccurrenceStatus.PENDING && previous.snoozedUntil == null ->
                    chores.reset(householdId, occurrence.chore.id, occurrence.key)
                previous.status == OccurrenceStatus.PENDING ->
                    chores.snooze(householdId, occurrence.chore.id, occurrence.key, previous.snoozedUntil!!)
                previous.status == OccurrenceStatus.SKIPPED -> chores.skip(householdId, occurrence.chore.id, occurrence.key)
                else -> chores.complete(householdId, occurrence.chore.id, occurrence.key, previous.completedAt ?: clock.instant(), previous.completedBy)
            }
        }
    }

    /**
     * Writes go straight to the local copy (the list updates from it) and sync in the background.
     * Any new action replaces a pending undo so an old snackbar can't restore a stale state.
     */
    private fun act(undo: UndoableAction? = null, write: suspend (householdId: String) -> Result<Unit>) {
        val householdId = _state.value.context?.householdId ?: return
        _state.update { it.copy(undo = undo) }
        viewModelScope.launch {
            writes.withLock { write(householdId) }.onFailure { e -> _state.update { it.copy(message = e.appError, undo = null) } }
        }
    }

    private companion object {
        const val LOOKBACK_DAYS = 7L
    }
}
