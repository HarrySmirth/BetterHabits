package app.betterhabits.data.allocation

import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.domain.allocation.AllocationEngine
import app.betterhabits.domain.allocation.AllocationMember
import app.betterhabits.domain.allocation.AllocationRequest
import app.betterhabits.domain.allocation.AllocationResult
import app.betterhabits.domain.allocation.HouseholdWorkload
import app.betterhabits.domain.allocation.WorkloadCalculator
import app.betterhabits.domain.model.AssignmentSource
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.OccurrenceStatus
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Assembles allocation inputs (chores, members with fair shares, preferences, availability, recent
 * history) and runs the pure [AllocationEngine] / [WorkloadCalculator]. Works offline from local data.
 */
class AllocationPlanner(
    private val chores: ChoreRepository,
    private val allocation: AllocationRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class Plan(val result: AllocationResult, val chores: List<Chore>, val inputs: AllocationInputs)

    /**
     * Proposes assignees for [choreIds] (null = every chore that isn't locked). [draft] is a chore being
     * created/edited that may not be saved yet; it replaces any stored version for planning.
     */
    suspend fun plan(details: HouseholdDetails, zone: ZoneId, choreIds: Set<String>? = null, draft: Chore? = null): Result<Plan> =
        runCatching {
            val householdId = details.household.id
            val inputs = allocation.inputs(householdId).getOrThrow()
            val stored = chores.observeChores(householdId, zone).first()
            val all = if (draft == null) stored else stored.filterNot { it.id == draft.id } + draft
            val today = clock.instant().atZone(zone).toLocalDate()
            val records = chores.observeRecords(householdId, today.minusDays(HISTORY_DAYS), today).first()

            val completed = records.filter { it.status == OccurrenceStatus.COMPLETED && it.completedBy != null }
            val minutesById = all.associate { it.id to it.effort.minutes }
            val request = AllocationRequest(
                chores = all,
                members = members(details, inputs),
                settings = inputs.settings,
                from = today,
                choreIds = choreIds,
                recentCompletedMinutes = completed.groupBy { it.completedBy!! }
                    .mapValues { (_, list) -> list.sumOf { minutesById[it.choreId] ?: 0 } },
                recentAssignees = completed.groupBy { it.choreId }
                    .mapValues { (_, list) -> list.sortedByDescending { it.key }.map { it.completedBy!! } },
            )
            Plan(AllocationEngine.allocate(request), all, inputs)
        }

    /** Current workload for the balance screen, over [from]..[to]. */
    suspend fun workload(details: HouseholdDetails, zone: ZoneId, from: LocalDate, to: LocalDate): Result<HouseholdWorkload> = runCatching {
        val householdId = details.household.id
        val inputs = allocation.inputs(householdId).getOrThrow()
        val list = chores.observeChores(householdId, zone).first()
        val records = chores.observeRecords(householdId, from, to).first()
        WorkloadCalculator.calculate(list, records, members(details, inputs), from, to, clock.instant())
    }

    /**
     * After a rotating chore is completed, hands it to whoever should go next: the allocator's rotation
     * rule (whoever did it last is less likely) balanced with everyone's workload.
     */
    suspend fun rotateAfterCompletion(details: HouseholdDetails, zone: ZoneId, choreId: String): Result<Unit> = runCatching {
        val plan = plan(details, zone, choreIds = setOf(choreId)).getOrThrow()
        apply(plan, setOf(choreId)).getOrThrow()
    }

    /** Applies accepted proposals: new assignee, marked as chosen by the allocator. */
    suspend fun apply(plan: Plan, accepted: Set<String>): Result<Unit> = runCatching {
        val byId = plan.chores.associateBy { it.id }
        plan.result.proposals.filter { it.choreId in accepted && it.changed }.forEach { proposal ->
            val chore = byId.getValue(proposal.choreId)
            chores.updateChore(chore, chore.copy(assigneeId = proposal.assigneeId, assignmentSource = AssignmentSource.AUTO)).getOrThrow()
        }
    }

    private fun members(details: HouseholdDetails, inputs: AllocationInputs) = details.members.map {
        AllocationMember(it.userId, it.displayName, it.workloadShare, inputs.preferencesOf(it.userId), inputs.availabilityOf(it.userId))
    }

    private companion object {
        const val HISTORY_DAYS = 28L
    }
}
