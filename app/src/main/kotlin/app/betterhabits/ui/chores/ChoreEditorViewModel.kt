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
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class RepeatKind { ONCE, DAILY, WEEKLY, MONTHLY, YEARLY }

enum class MonthlyMode { DAY_OF_MONTH, WEEKDAY }

data class ChoreForm(
    val name: String = "",
    val repeat: RepeatKind = RepeatKind.WEEKLY,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val startDate: LocalDate = LocalDate.MIN,
    val endDate: LocalDate? = null,
    val times: List<LocalTime> = emptyList(),
    val minutes: Int? = 15,
    val assigneeId: String? = null,
    val category: ChoreCategory = ChoreCategory.OTHER,
    val difficulty: Int = Chore.DEFAULT_DIFFICULTY,
    val points: Int = 0,
    val description: String = "",
    val checklist: List<String> = emptyList(),
    val notes: String = "",
) {
    val nameValid get() = name.isNotBlank() && name.trim().length <= Chore.MAX_NAME_LENGTH
    val minutesValid get() = minutes != null && minutes in 1..Chore.MAX_MINUTES
    val weekdaysValid get() = repeat != RepeatKind.WEEKLY || weekdays.isNotEmpty()
    val endDateValid get() = endDate == null || !endDate.isBefore(startDate)
    val isValid get() = nameValid && minutesValid && weekdaysValid && endDateValid

    /** "Third Tuesday" style anchor derived from the start date; the 5th weekday counts as "last". */
    val weekOrdinal: Int get() = ((startDate.dayOfMonth - 1) / 7 + 1).let { if (it > 4) -1 else it }

    fun recurrence(): Recurrence = when (repeat) {
        RepeatKind.ONCE -> Recurrence.Once
        RepeatKind.DAILY -> Recurrence.Daily(interval)
        RepeatKind.WEEKLY -> Recurrence.Weekly(weekdays, interval)
        RepeatKind.MONTHLY -> when (monthlyMode) {
            MonthlyMode.DAY_OF_MONTH -> Recurrence.MonthlyOnDay(startDate.dayOfMonth, interval)
            MonthlyMode.WEEKDAY -> Recurrence.MonthlyOnWeekday(weekOrdinal, startDate.dayOfWeek, interval)
        }
        RepeatKind.YEARLY -> Recurrence.Yearly(startDate.monthValue, startDate.dayOfMonth, interval)
    }

    companion object {
        fun from(chore: Chore): ChoreForm {
            val r = chore.schedule.recurrence
            return ChoreForm(
                name = chore.name,
                repeat = when (r) {
                    Recurrence.Once -> RepeatKind.ONCE
                    is Recurrence.Daily -> RepeatKind.DAILY
                    is Recurrence.Weekly -> RepeatKind.WEEKLY
                    is Recurrence.MonthlyOnDay, is Recurrence.MonthlyOnWeekday -> RepeatKind.MONTHLY
                    is Recurrence.Yearly -> RepeatKind.YEARLY
                },
                interval = r.interval,
                weekdays = (r as? Recurrence.Weekly)?.days.orEmpty(),
                monthlyMode = if (r is Recurrence.MonthlyOnWeekday) MonthlyMode.WEEKDAY else MonthlyMode.DAY_OF_MONTH,
                startDate = chore.schedule.startDate,
                endDate = chore.schedule.endDate,
                times = chore.schedule.timesOfDay,
                minutes = chore.effort.minutes,
                assigneeId = chore.assigneeId,
                category = chore.category,
                difficulty = chore.difficulty,
                points = chore.points,
                description = chore.description.orEmpty(),
                checklist = chore.checklist,
                notes = chore.notes.orEmpty(),
            )
        }
    }
}

data class ChoreEditorUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val existing: Chore? = null,
    val form: ChoreForm = ChoreForm(),
    val showAdvanced: Boolean = false,
    val showValidation: Boolean = false,
    val saving: Boolean = false,
    val saveError: AppError? = null,
    val saved: Boolean = false,
) {
    val isNew get() = existing == null
    val canEdit get() = context?.details?.iCan(if (isNew) HouseholdPermission.CREATE_CHORES else HouseholdPermission.EDIT_CHORES) == true
    val canAssign get() = context?.details?.iCan(HouseholdPermission.ASSIGN_CHORES) == true || (isNew && canEdit)
}

class ChoreEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    /** Route arg (see ChoreEditorRoute); null when creating. */
    private val choreId: String? = savedStateHandle.get<String>("choreId")

    private val _state = MutableStateFlow(ChoreEditorUiState())
    val state: StateFlow<ChoreEditorUiState> = _state.asStateFlow()

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
            if (choreId == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        context = context,
                        form = ChoreForm(startDate = today, weekdays = setOf(today.dayOfWeek), assigneeId = null),
                    )
                }
            } else {
                chores.chore(choreId, context.zone)
                    .onSuccess { chore ->
                        _state.update { it.copy(loading = false, context = context, existing = chore, form = ChoreForm.from(chore)) }
                    }
                    .onFailure { e -> _state.update { it.copy(loading = false, loadError = e.appError) } }
            }
        }
    }

    private fun edit(transform: (ChoreForm) -> ChoreForm) = _state.update { it.copy(form = transform(it.form), saveError = null) }

    fun onName(value: String) = edit { it.copy(name = value.take(Chore.MAX_NAME_LENGTH)) }
    fun onRepeat(kind: RepeatKind) = edit { it.copy(repeat = kind, interval = 1) }
    fun onInterval(value: Int) = edit { it.copy(interval = value.coerceIn(1, Recurrence.MAX_INTERVAL)) }
    fun onToggleWeekday(day: DayOfWeek) = edit { it.copy(weekdays = if (day in it.weekdays) it.weekdays - day else it.weekdays + day) }
    fun onMonthlyMode(mode: MonthlyMode) = edit { it.copy(monthlyMode = mode) }
    fun onStartDate(date: LocalDate) = edit { it.copy(startDate = date) }
    fun onEndDate(date: LocalDate?) = edit { it.copy(endDate = date) }
    fun onAddTime(time: LocalTime) = edit { it.copy(times = (it.times + time).distinct().sorted()) }
    fun onRemoveTime(time: LocalTime) = edit { it.copy(times = it.times - time) }
    fun onMinutes(value: Int?) = edit { it.copy(minutes = value) }
    fun onAssignee(userId: String?) = edit { it.copy(assigneeId = userId) }
    fun onCategory(category: ChoreCategory) = edit { it.copy(category = category) }
    fun onDifficulty(value: Int) = edit { it.copy(difficulty = value.coerceIn(1, 5)) }
    fun onPoints(value: Int) = edit { it.copy(points = value.coerceIn(0, 10_000)) }
    fun onDescription(value: String) = edit { it.copy(description = value.take(1000)) }
    fun onNotes(value: String) = edit { it.copy(notes = value.take(2000)) }
    fun onAddChecklistItem(text: String) = edit { if (text.isBlank() || it.checklist.size >= 30) it else it.copy(checklist = it.checklist + text.trim()) }
    fun onRemoveChecklistItem(index: Int) = edit { it.copy(checklist = it.checklist.filterIndexed { i, _ -> i != index }) }
    fun toggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }

    fun save() {
        val s = _state.value
        val context = s.context ?: return
        if (!s.form.isValid) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        val form = s.form
        val chore = Chore(
            id = s.existing?.id ?: UUID.randomUUID().toString(),
            householdId = context.householdId,
            name = form.name.trim(),
            description = form.description.trim().ifEmpty { null },
            category = form.category,
            effort = Effort(form.minutes!!),
            difficulty = form.difficulty,
            points = form.points,
            schedule = Schedule(form.recurrence(), form.startDate, form.endDate, form.times, context.zone),
            assigneeId = form.assigneeId,
            checklist = form.checklist,
            notes = form.notes.trim().ifEmpty { null },
            requiresProof = s.existing?.requiresProof ?: false,
            active = s.existing?.active ?: true,
        )
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = if (s.isNew) chores.createChore(chore) else chores.updateChore(chore)
            result
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, saveError = e.appError) } }
        }
    }
}
