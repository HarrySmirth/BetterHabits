package app.betterhabits.domain.allocation

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.schedule.ScheduleCalculator
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** Why the allocator chose someone. Rendered as plain sentences in the UI. */
sealed interface AllocationReason {
    /** The assignment is locked; the allocator doesn't touch it. */
    data object Locked : AllocationReason

    /** Nobody can take it (everyone excluded, unable, or away). */
    data object NoOneEligible : AllocationReason

    /** Everyone else is excluded, can't do it, or is away. */
    data object OnlyOneEligible : AllocationReason

    data class Prefers(val level: PreferenceLevel) : AllocationReason

    /** The next-best person likes it less. */
    data class OthersPreferLess(val runnerUpId: String, val runnerUpLevel: PreferenceLevel) : AllocationReason

    /** They had less scheduled work (minutes per week, adjusted for fair share) than the next-best person. */
    data class LessWork(val minutesPerWeek: Int, val runnerUpId: String) : AllocationReason

    /** Rotation: the next-best person did it last time. */
    data class TakesTurn(val lastDoneBy: String) : AllocationReason

    /** The next-best person is unavailable for more of its dates. */
    data class AvailableWhenOthersNot(val runnerUpId: String) : AllocationReason

    /** It's due at a time of day they said suits them. */
    data object SuitsTheirTime : AllocationReason

    /** A disliked chore went to whoever has had fewer disliked chores, so nobody avoids them all. */
    data object SharesUndesirableWork : AllocationReason

    /** Fallback when the decision came down to keeping totals even. */
    data object KeepsWorkloadBalanced : AllocationReason
}

data class AllocationProposal(
    val choreId: String,
    val assigneeId: String?,
    val previousAssigneeId: String?,
    val reasons: List<AllocationReason>,
) {
    val changed: Boolean get() = assigneeId != previousAssigneeId
}

/** Expected weekly load per member after the proposals are applied. */
data class ProjectedLoad(val memberId: String, val minutesPerWeek: Int, val undesirableMinutesPerWeek: Int, val choreCount: Int)

data class AllocationResult(val proposals: List<AllocationProposal>, val projected: List<ProjectedLoad>)

data class AllocationRequest(
    /** All the household's active chores: locked/unselected ones count towards existing load. */
    val chores: List<Chore>,
    val members: List<AllocationMember>,
    val settings: AllocationSettings = AllocationSettings(),
    /** Start of the planning window (household-local). Loads are averaged over [windowDays]. */
    val from: LocalDate,
    val windowDays: Int = 28,
    /** Chores the allocator may (re)assign; null = every chore that isn't locked. */
    val choreIds: Set<String>? = null,
    /** Minutes each member actually completed recently (e.g. last 4 weeks), for gentle catch-up. */
    val recentCompletedMinutes: Map<String, Int> = emptyMap(),
    /** Most recent assignees of each chore, newest first (for rotation). */
    val recentAssignees: Map<String, List<String>> = emptyMap(),
)

/**
 * Deterministic, explainable chore allocation.
 *
 * Primary objective: balance estimated effort (minutes per week, scaled by difficulty and by each
 * member's fair share). Preferences, availability, time-of-day fit, rotation and sharing of
 * disliked chores adjust a candidate's cost, but only among candidates within a fairness band of
 * the most balanced choice, so preferences can't push anyone far past their fair share.
 * "Can't do", exclusions and locks are hard constraints.
 *
 * Greedy largest-first assignment: big chores are placed first, which keeps totals even.
 */
object AllocationEngine {

    /** How much a one-step preference is worth, as a fraction of the chore's load at weight 100. */
    private const val PREFERENCE_SCALE = 0.5

    /** Extra cost per fraction of a chore's dates the person is unavailable for. */
    private const val UNAVAILABILITY_SCALE = 2.0

    private const val TIME_FIT_SCALE = 0.15
    private const val ROTATION_SCALE = 0.6
    private const val UNDESIRABLE_SHARING_SCALE = 0.5

    /** Recent history nudges starting loads by this fraction of the difference, capped. */
    private const val HISTORY_FACTOR = 0.25
    private const val HISTORY_CAP_MINUTES = 60.0

    /** Fairness band: preferences may pick someone at most this much above the most balanced choice. */
    private const val BAND_MIN_MINUTES = 20.0
    private const val BAND_FRACTION = 0.15

    private const val LESS_WORK_THRESHOLD = 5

    fun allocate(request: AllocationRequest): AllocationResult {
        val members = request.members.sortedBy { it.id }
        val memberIds = members.map { it.id }.toSet()
        val weeks = request.windowDays / 7.0

        fun weeklyLoad(chore: Chore): Double =
            ScheduleCalculator.occurrencesBetween(chore.schedule, request.from, request.from.plusDays(request.windowDays - 1L)).size *
                chore.effort.minutes * difficultyFactor(chore.difficulty) / weeks

        val active = request.chores.filter { it.active }
        val allocatable = active.filter { chore ->
            !(chore.assignmentLocked && chore.assigneeId in memberIds) && (request.choreIds == null || chore.id in request.choreIds)
        }
        val fixed = active - allocatable.toSet()

        val load = members.associate { it.id to 0.0 }.toMutableMap()
        val undesirable = members.associate { it.id to 0.0 }.toMutableMap()
        val count = members.associate { it.id to 0 }.toMutableMap()

        fun addLoad(memberId: String, chore: Chore, w: Double) {
            load[memberId] = load.getValue(memberId) + w
            count[memberId] = count.getValue(memberId) + 1
            val level = members.first { it.id == memberId }.preferences.forChore(chore)
            if (level.isUndesirable) undesirable[memberId] = undesirable.getValue(memberId) + w
        }

        fixed.forEach { chore -> chore.assigneeId?.takeIf { it in memberIds }?.let { addLoad(it, chore, weeklyLoad(chore)) } }

        // Gentle catch-up: whoever did more recently counts as a little busier, so gets less new work (bounded).
        val history = members.associate { it.id to (request.recentCompletedMinutes[it.id] ?: 0) / weeks / it.share }
        val meanHistory = if (history.isEmpty()) 0.0 else history.values.average()
        val startOffset = members.associate {
            it.id to ((history.getValue(it.id) - meanHistory) * HISTORY_FACTOR).coerceIn(-HISTORY_CAP_MINUTES, HISTORY_CAP_MINUTES)
        }

        val proposals = mutableListOf<AllocationProposal>()
        fixed.filter { it.assignmentLocked && it.assigneeId in memberIds && (request.choreIds == null || it.id in request.choreIds) }
            .forEach { proposals += AllocationProposal(it.id, it.assigneeId, it.assigneeId, listOf(AllocationReason.Locked)) }

        val ordered = allocatable.map { it to weeklyLoad(it) }.sortedWith(compareByDescending<Pair<Chore, Double>> { it.second }.thenBy { it.first.id })
        val weight = request.settings.preferenceWeight / 100.0

        for ((chore, w) in ordered) {
            val dates = ScheduleCalculator.occurrencesBetween(chore.schedule, request.from, request.from.plusDays(request.windowDays - 1L))
            val candidates = members.mapNotNull { member ->
                val level = member.preferences.forChore(chore)
                if (member.id in chore.excludedMemberIds || level == PreferenceLevel.CANNOT_DO) return@mapNotNull null
                val availableRatio = if (dates.isEmpty()) 1.0 else dates.count { member.availability.isAvailableOn(it.key.date) }.toDouble() / dates.size
                if (availableRatio == 0.0 && dates.isNotEmpty()) return@mapNotNull null
                val timeFit = dates.mapNotNull { member.availability.prefersTime(it.key.time) }.let { fits ->
                    if (fits.isEmpty()) null else fits.count { it } > fits.size / 2
                }
                Candidate(member, level, availableRatio, timeFit)
            }

            if (candidates.isEmpty()) {
                proposals += AllocationProposal(chore.id, null, chore.assigneeId, listOf(AllocationReason.NoOneEligible))
                continue
            }

            val effectiveW = max(w, 1.0)
            val lastAssignee = request.recentAssignees[chore.id]?.firstOrNull()
            val meanUndesirable = undesirable.values.average()

            candidates.forEach { c ->
                val share = c.member.share
                c.loadBefore = (load.getValue(c.member.id) + startOffset.getValue(c.member.id)) / share
                c.balance = c.loadBefore + effectiveW / share
                var adjust = 0.0
                adjust += c.level.score * weight * PREFERENCE_SCALE * effectiveW
                adjust += (1 - c.availableRatio) * UNAVAILABILITY_SCALE * effectiveW
                c.timeFit?.let { adjust += if (it) -TIME_FIT_SCALE * weight * effectiveW else TIME_FIT_SCALE * weight * effectiveW }
                if (chore.rotate && lastAssignee == c.member.id) adjust += ROTATION_SCALE * effectiveW
                if (c.level.isUndesirable && !request.settings.allowAvoidance) {
                    adjust += UNDESIRABLE_SHARING_SCALE * (undesirable.getValue(c.member.id) - meanUndesirable) / share
                }
                c.cost = c.balance + adjust / share
            }

            val mostBalanced = candidates.minWith(compareBy<Candidate> { it.balance }.thenBy { it.member.id })
            val averageLoad = candidates.map { it.balance }.average()
            val band = max(BAND_MIN_MINUTES, BAND_FRACTION * averageLoad)
            val allowed = candidates.filter { it.balance <= mostBalanced.balance + band }
            val ranking = compareBy<Candidate> { it.cost }.thenBy { it.balance }.thenBy { it.member.id }
            val winner = allowed.minWith(ranking)
            val runnerUp = candidates.filter { it !== winner }.minWithOrNull(ranking)

            addLoad(winner.member.id, chore, w)
            proposals += AllocationProposal(chore.id, winner.member.id, chore.assigneeId, explain(chore, winner, runnerUp, lastAssignee, request.settings))
        }

        val projected = members.map {
            ProjectedLoad(it.id, load.getValue(it.id).roundToInt(), undesirable.getValue(it.id).roundToInt(), count.getValue(it.id))
        }
        val order = request.chores.map { it.id }
        return AllocationResult(proposals.sortedBy { order.indexOf(it.choreId) }, projected)
    }

    private fun explain(
        chore: Chore,
        winner: Candidate,
        runnerUp: Candidate?,
        lastAssignee: String?,
        settings: AllocationSettings,
    ): List<AllocationReason> {
        if (runnerUp == null) return listOf(AllocationReason.OnlyOneEligible)
        val reasons = mutableListOf<AllocationReason>()
        if (settings.preferenceWeight > 0 && winner.level.score < runnerUp.level.score) {
            reasons += if (winner.level.score < 0) AllocationReason.Prefers(winner.level)
            else AllocationReason.OthersPreferLess(runnerUp.member.id, runnerUp.level)
        }
        val lessWork = (runnerUp.loadBefore - winner.loadBefore).roundToInt()
        if (lessWork >= LESS_WORK_THRESHOLD) reasons += AllocationReason.LessWork(lessWork, runnerUp.member.id)
        if (chore.rotate && lastAssignee != null && lastAssignee == runnerUp.member.id) reasons += AllocationReason.TakesTurn(lastAssignee)
        if (winner.availableRatio > runnerUp.availableRatio) reasons += AllocationReason.AvailableWhenOthersNot(runnerUp.member.id)
        if (settings.preferenceWeight > 0 && winner.timeFit == true && runnerUp.timeFit != true) reasons += AllocationReason.SuitsTheirTime
        if (!settings.allowAvoidance && winner.level.isUndesirable && runnerUp.level.isUndesirable) reasons += AllocationReason.SharesUndesirableWork
        if (reasons.isEmpty()) reasons += AllocationReason.KeepsWorkloadBalanced
        return reasons
    }

    /** Harder chores count for a bit more (difficulty 1 = -20%, 3 = as estimated, 5 = +20%). */
    fun difficultyFactor(difficulty: Int): Double = 1.0 + 0.1 * (difficulty - 3)

    private class Candidate(
        val member: AllocationMember,
        val level: PreferenceLevel,
        val availableRatio: Double,
        val timeFit: Boolean?,
    ) {
        var loadBefore = 0.0
        var balance = 0.0
        var cost = 0.0
    }
}
