package app.betterhabits.ui.today

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
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.model.sum
import app.betterhabits.domain.schedule.OccurrenceResolver
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.loadContext
import app.betterhabits.ui.chores.selectedHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val message: AppError? = null,
    val undo: UndoableAction? = null,
) {
    val visible: List<ChoreOccurrence>
        get() = if (filter == AgendaFilter.EVERYONE) agenda else agenda.filter { it.assigneeId == context?.myId }
    val overdue get() = visible.filter { it.state == OccurrenceState.OVERDUE }
    val dueToday get() = visible.filter { it.state == OccurrenceState.UPCOMING }
    val done get() = visible.filter { it.isDone }
    /** Today's progress by count and by estimated effort (effort is the fairness measure). */
    val doneCount get() = done.size
    val totalCount get() = visible.size
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
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private var choreList: List<Chore> = emptyList()
    private var records: List<OccurrenceRecord> = emptyList()
    private var selected: Pair<String, String>? = null
    private val writes = Mutex()

    init {
        viewModelScope.launch {
            session.selectedHousehold().collect {
                selected = it
                _state.update { s -> s.copy(loading = true, context = null, agenda = emptyList(), loadError = null) }
                load()
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val (householdId, userId) = selected ?: return
        val context = households.loadContext(householdId, userId).getOrElse { e ->
            _state.update { it.copy(loading = false, refreshing = false, loadError = e.appError) }
            return
        }
        val today = clock.instant().atZone(context.zone).toLocalDate()
        val loadedChores = chores.chores(householdId, context.zone)
        val loadedRecords = chores.records(householdId, today.minusDays(LOOKBACK_DAYS), today)
        val error = loadedChores.exceptionOrNull() ?: loadedRecords.exceptionOrNull()
        if (error != null) {
            _state.update { s ->
                if (s.context != null) s.copy(refreshing = false, message = error.appError)
                else s.copy(loading = false, refreshing = false, loadError = error.appError)
            }
            return
        }
        choreList = loadedChores.getOrThrow()
        records = loadedRecords.getOrThrow()
        _state.update { s ->
            s.copy(
                loading = false,
                refreshing = false,
                loadError = null,
                context = context,
                // Children see their own list; adults default to theirs but can switch.
                filter = if (s.context == null && context.isChild) AgendaFilter.MINE else s.filter,
            )
        }
        recompute()
    }

    private fun recompute() {
        val context = _state.value.context ?: return
        val now = clock.instant()
        val today = now.atZone(context.zone).toLocalDate()
        _state.update {
            it.copy(today = today, agenda = OccurrenceResolver.agenda(choreList, records, today, now, LOOKBACK_DAYS))
        }
    }

    fun setFilter(filter: AgendaFilter) = _state.update { it.copy(filter = filter) }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun undoShown() = _state.update { it.copy(undo = null) }

    /**
     * The checkbox: ticked means completed. Unticking a completed occurrence makes it pending again;
     * ticking anything else (pending, overdue or skipped) completes it.
     */
    fun toggle(occurrence: ChoreOccurrence) {
        if (occurrence.state == OccurrenceState.COMPLETED) {
            reset(occurrence)
        } else {
            val context = _state.value.context ?: return
            val now = clock.instant()
            val updated = (occurrence.record ?: OccurrenceRecord(occurrence.chore.id, occurrence.key, OccurrenceStatus.PENDING))
                .copy(status = OccurrenceStatus.COMPLETED, completedBy = context.myId, completedAt = now, snoozedUntil = null)
            apply(occurrence, updated, offerUndo = true, completed = true) {
                chores.complete(it.householdId, occurrence.chore.id, occurrence.key, now)
            }
        }
    }

    /** Back to pending, e.g. "Undo" on a skipped occurrence. */
    fun reset(occurrence: ChoreOccurrence) {
        apply(occurrence, occurrence.record?.copy(status = OccurrenceStatus.PENDING, completedBy = null, completedAt = null, snoozedUntil = null)) {
            chores.reset(it.householdId, occurrence.chore.id, occurrence.key)
        }
    }

    fun skip(occurrence: ChoreOccurrence) {
        val updated = (occurrence.record ?: OccurrenceRecord(occurrence.chore.id, occurrence.key, OccurrenceStatus.PENDING))
            .copy(status = OccurrenceStatus.SKIPPED, completedBy = null, completedAt = null, snoozedUntil = null)
        apply(occurrence, updated, offerUndo = true) { chores.skip(it.householdId, occurrence.chore.id, occurrence.key) }
    }

    fun snooze(occurrence: ChoreOccurrence, option: SnoozeOption) {
        val context = _state.value.context ?: return
        val now = clock.instant()
        val until: Instant = when (option) {
            SnoozeOption.ONE_HOUR -> now.plus(Duration.ofHours(1))
            SnoozeOption.TOMORROW_MORNING ->
                now.atZone(clock.zone).toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(clock.zone).toInstant()
        }
        val updated = (occurrence.record ?: OccurrenceRecord(occurrence.chore.id, occurrence.key, OccurrenceStatus.PENDING))
            .copy(status = OccurrenceStatus.PENDING, snoozedUntil = until)
        apply(occurrence, updated, offerUndo = true) { chores.snooze(context.householdId, occurrence.chore.id, occurrence.key, until) }
    }

    fun undo(action: UndoableAction) {
        _state.update { it.copy(undo = null) }
        val occurrence = action.occurrence
        apply(occurrence, action.previous) {
            val previous = action.previous
            when {
                previous == null || previous.status == OccurrenceStatus.PENDING && previous.snoozedUntil == null ->
                    chores.reset(it.householdId, occurrence.chore.id, occurrence.key)
                previous.status == OccurrenceStatus.PENDING ->
                    chores.snooze(it.householdId, occurrence.chore.id, occurrence.key, previous.snoozedUntil!!)
                previous.status == OccurrenceStatus.SKIPPED -> chores.skip(it.householdId, occurrence.chore.id, occurrence.key)
                else -> chores.complete(it.householdId, occurrence.chore.id, occurrence.key, previous.completedAt ?: clock.instant(), previous.completedBy)
            }
        }
    }

    /**
     * Optimistically applies [updated] locally, then persists; rolls back with a message on failure.
     * Writes go through [writes] one at a time in call order, so rapid taps reach the server in
     * the order they were made and the final server state matches the last tap.
     */
    private fun apply(
        occurrence: ChoreOccurrence,
        updated: OccurrenceRecord?,
        offerUndo: Boolean = false,
        completed: Boolean = false,
        persist: suspend (HouseholdContext) -> Result<Unit>,
    ) {
        val context = _state.value.context ?: return
        val before = records
        records = records.filterNot { it.choreId == occurrence.chore.id && it.key == occurrence.key } + listOfNotNull(updated)
        recompute()
        // Any new action replaces a pending undo, so an old snackbar can't restore a stale state.
        _state.update { it.copy(undo = if (offerUndo) UndoableAction(occurrence, occurrence.record, completed) else null) }
        viewModelScope.launch {
            writes.withLock { persist(context) }.onFailure { e ->
                records = before
                recompute()
                _state.update { it.copy(message = e.appError, undo = null) }
            }
        }
    }

    private companion object {
        const val LOOKBACK_DAYS = 7L
    }
}
