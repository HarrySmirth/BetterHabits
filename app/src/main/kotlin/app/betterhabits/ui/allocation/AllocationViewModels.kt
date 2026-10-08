package app.betterhabits.ui.allocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.allocation.AllocationPlanner
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.allocation.AllocationProposal
import app.betterhabits.domain.allocation.HouseholdWorkload
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.loadContext
import app.betterhabits.ui.chores.selectedHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

enum class BalancePeriod { THIS_WEEK, LAST_FOUR_WEEKS }

data class BalanceUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val period: BalancePeriod = BalancePeriod.THIS_WEEK,
    val workload: HouseholdWorkload? = null,
) {
    val canSuggest get() = context?.details?.iCan(HouseholdPermission.ASSIGN_CHORES) == true
}

/** Workload and fairness per member, for this week or the last four. */
class BalanceViewModel(
    private val planner: AllocationPlanner,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(BalanceUiState())
    val state: StateFlow<BalanceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setPeriod(period: BalancePeriod) {
        _state.update { it.copy(period = period) }
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
            val (from, to) = when (_state.value.period) {
                BalancePeriod.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
                BalancePeriod.LAST_FOUR_WEEKS -> today.minusDays(27) to today
            }
            planner.workload(context.details, context.zone, from, to)
                .onSuccess { w -> _state.update { it.copy(loading = false, loadError = null, context = context, workload = w) } }
                .onFailure { e -> _state.update { it.copy(loading = false, loadError = e.appError) } }
        }
    }
}

data class ReviewUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val onlyUnassigned: Boolean = false,
    val plan: AllocationPlanner.Plan? = null,
    /** Chore ids whose proposed change the user accepts (all changes by default). */
    val accepted: Set<String> = emptySet(),
    val applying: Boolean = false,
    val applied: Boolean = false,
    val message: AppError? = null,
) {
    val choresById: Map<String, Chore> get() = plan?.chores?.associateBy { it.id }.orEmpty()
    val changes: List<AllocationProposal> get() = plan?.result?.proposals?.filter { it.changed }.orEmpty()
    val unchanged: List<AllocationProposal> get() = plan?.result?.proposals?.filterNot { it.changed }.orEmpty()
}

/** Runs the allocator and lets the user accept or reject each suggested change. */
class AllocationReviewViewModel(
    private val planner: AllocationPlanner,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setOnlyUnassigned(only: Boolean) {
        _state.update { it.copy(onlyUnassigned = only) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val (householdId, userId) = session.selectedHousehold().first()
            val context = households.loadContext(householdId, userId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            val firstPass = planner.plan(context.details, context.zone).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            val plan = if (_state.value.onlyUnassigned) {
                val unassigned = firstPass.chores.filter { it.active && it.assigneeId == null }.map { it.id }.toSet()
                planner.plan(context.details, context.zone, choreIds = unassigned).getOrNull() ?: firstPass
            } else {
                firstPass
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadError = null,
                    context = context,
                    plan = plan,
                    accepted = plan.result.proposals.filter { p -> p.changed }.map { p -> p.choreId }.toSet(),
                )
            }
        }
    }

    fun toggle(choreId: String) = _state.update {
        it.copy(accepted = if (choreId in it.accepted) it.accepted - choreId else it.accepted + choreId)
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun apply() {
        val plan = _state.value.plan ?: return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            planner.apply(plan, _state.value.accepted)
                .onSuccess { _state.update { it.copy(applying = false, applied = true) } }
                .onFailure { e -> _state.update { it.copy(applying = false, message = e.appError) } }
        }
    }
}
