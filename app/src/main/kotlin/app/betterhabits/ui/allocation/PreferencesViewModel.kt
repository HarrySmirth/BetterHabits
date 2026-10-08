package app.betterhabits.ui.allocation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.allocation.AllocationRepository
import app.betterhabits.data.allocation.AwayPeriod
import app.betterhabits.data.allocation.PreferenceTarget
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.template.TemplateRepository
import app.betterhabits.domain.allocation.Availability
import app.betterhabits.domain.allocation.DateRange
import app.betterhabits.domain.allocation.MemberPreferences
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.allocation.TimeOfDay
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.loadContext
import app.betterhabits.ui.chores.selectedHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek

data class PreferencesUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val memberId: String = "",
    val memberName: String = "",
    val chores: List<Chore> = emptyList(),
    val preferences: MemberPreferences = MemberPreferences(""),
    val availability: Availability = Availability(""),
    val awayPeriods: List<AwayPeriod> = emptyList(),
    /** Template names by id, for showing template preferences. */
    val templateNames: Map<String, String> = emptyMap(),
    val message: AppError? = null,
) {
    val isMe get() = context?.myId == memberId
    /** Your own, or a child's when you manage children (matches the server rule). */
    val canEdit: Boolean
        get() = isMe || (context?.details?.iCan(HouseholdPermission.MANAGE_CHILDREN) == true &&
            context.member(memberId)?.role == HouseholdRole.CHILD)

    /** Chores grouped by category, categories in display order. */
    val choresByCategory: List<Pair<ChoreCategory, List<Chore>>>
        get() = ChoreCategory.entries.mapNotNull { c -> chores.filter { it.category == c && it.active }.takeIf { it.isNotEmpty() }?.let { c to it } }
}

class PreferencesViewModel(
    savedStateHandle: SavedStateHandle,
    private val allocation: AllocationRepository,
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val templates: TemplateRepository,
) : ViewModel() {

    /** Route arg (see PreferencesRoute); null = the signed-in user. */
    private val requestedMember: String? = savedStateHandle.get<String>("memberId")

    private val _state = MutableStateFlow(PreferencesUiState())
    val state: StateFlow<PreferencesUiState> = _state.asStateFlow()

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
            val memberId = requestedMember ?: userId
            val inputs = allocation.inputs(householdId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadError = null,
                    context = context,
                    memberId = memberId,
                    memberName = context.member(memberId)?.displayName.orEmpty(),
                    chores = chores.observeChores(householdId, context.zone).first(),
                    preferences = inputs.preferencesOf(memberId),
                    availability = inputs.availabilityOf(memberId),
                    awayPeriods = inputs.awayPeriods.filter { a -> a.memberId == memberId }.sortedBy { a -> a.range.start },
                    templateNames = templates.templates(householdId).getOrNull().orEmpty().associate { t -> t.id to t.name },
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun setChorePreference(choreId: String, level: PreferenceLevel?) = edit(
        local = { p -> p.copy(byChore = if (level == null) p.byChore - choreId else p.byChore + (choreId to level)) },
        remote = { h, m -> allocation.setPreference(h, m, PreferenceTarget.ChoreTarget(choreId), level) },
    )

    fun setCategoryPreference(category: ChoreCategory, level: PreferenceLevel?) = edit(
        local = { p -> p.copy(byCategory = if (level == null) p.byCategory - category else p.byCategory + (category to level)) },
        remote = { h, m -> allocation.setPreference(h, m, PreferenceTarget.CategoryTarget(category), level) },
    )

    fun setTemplatePreference(templateId: String, level: PreferenceLevel?) = edit(
        local = { p -> p.copy(byTemplate = if (level == null) p.byTemplate - templateId else p.byTemplate + (templateId to level)) },
        remote = { h, m -> allocation.setPreference(h, m, PreferenceTarget.TemplateTarget(templateId), level) },
    )

    fun toggleUnavailableDay(day: DayOfWeek) {
        val a = _state.value.availability
        saveAvailability(a.copy(unavailableDays = if (day in a.unavailableDays) a.unavailableDays - day else a.unavailableDays + day))
    }

    fun togglePreferredTime(time: TimeOfDay) {
        val a = _state.value.availability
        saveAvailability(a.copy(preferredTimes = if (time in a.preferredTimes) a.preferredTimes - time else a.preferredTimes + time))
    }

    fun addAwayPeriod(range: DateRange, note: String?) = mutateThenReload { h, m -> allocation.addAwayPeriod(h, m, range, note) }

    fun removeAwayPeriod(period: AwayPeriod) = mutateThenReload { _, _ -> allocation.removeAwayPeriod(period.id) }

    private fun saveAvailability(updated: Availability) {
        val previous = _state.value.availability
        _state.update { it.copy(availability = updated) }
        launchRemote(onFailure = { _state.update { it.copy(availability = previous) } }) { h, m ->
            allocation.setAvailability(h, m, updated.unavailableDays, updated.preferredTimes)
        }
    }

    /** Optimistic: shows the change at once and reverts it if the server refuses. */
    private fun edit(local: (MemberPreferences) -> MemberPreferences, remote: suspend (String, String) -> Result<Unit>) {
        val previous = _state.value.preferences
        _state.update { it.copy(preferences = local(previous)) }
        launchRemote(onFailure = { _state.update { it.copy(preferences = previous) } }, remote)
    }

    private fun mutateThenReload(remote: suspend (String, String) -> Result<Unit>) =
        launchRemote(onFailure = {}, remote = { h, m -> remote(h, m).onSuccess { load() } })

    private fun launchRemote(onFailure: () -> Unit, remote: suspend (householdId: String, memberId: String) -> Result<Unit>) {
        val s = _state.value
        val householdId = s.context?.householdId ?: return
        if (!s.canEdit) return
        viewModelScope.launch {
            remote(householdId, s.memberId).onFailure { e ->
                onFailure()
                _state.update { it.copy(message = e.appError) }
            }
        }
    }
}
