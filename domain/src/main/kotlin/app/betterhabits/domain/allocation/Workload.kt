package app.betterhabits.domain.allocation

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.schedule.OccurrenceResolver
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class MemberWorkload(
    val memberId: String,
    /** Estimated minutes of chores assigned to them in the period (the primary fairness measure). */
    val estimatedMinutes: Int,
    val choreCount: Int,
    val points: Int,
    /** Average difficulty (1..5) of their chores, or null if they have none. */
    val averageDifficulty: Double?,
    /** Occurrences of chores they dislike or hate. */
    val undesirableCount: Int,
    val completed: Int,
    val missed: Int,
    /** Minutes of work they actually did, including chores assigned to others. */
    val completedMinutes: Int,
) {
    /** Share of their due chores done, or null if nothing was due yet. */
    val completionRate: Double? get() = (completed + missed).takeIf { it > 0 }?.let { completed.toDouble() / it }
}

data class HouseholdWorkload(
    val members: List<MemberWorkload>,
    /** Fair share per member (their share weight / total), summing to 1. */
    val fairShares: Map<String, Double>,
    val unassignedMinutes: Int,
) {
    val totalMinutes: Int get() = members.sumOf { it.estimatedMinutes }

    /** Each member's actual share of assigned minutes (0..1). */
    fun actualShare(memberId: String): Double =
        if (totalMinutes == 0) 0.0 else (members.first { it.memberId == memberId }.estimatedMinutes.toDouble() / totalMinutes)

    /**
     * 1.0 = everyone exactly at their fair share; lower = more uneven. Half the sum of absolute
     * differences between actual and fair shares (0..1), subtracted from 1. An estimate, not a verdict.
     */
    val balanceScore: Double
        get() = if (totalMinutes == 0) 1.0
        else 1.0 - members.sumOf { abs(actualShare(it.memberId) - fairShares.getValue(it.memberId)) } / 2
}

/** Measures who has (and has done) what over a period, from schedules plus completion records. */
object WorkloadCalculator {

    fun calculate(
        chores: List<Chore>,
        records: List<OccurrenceRecord>,
        members: List<AllocationMember>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
    ): HouseholdWorkload {
        val occurrences = OccurrenceResolver.resolve(chores, records, from, to, now)
        val byId = members.associateBy { it.id }
        val totalShare = members.sumOf { it.share }

        val workloads = members.map { member ->
            val mine = occurrences.filter { it.assigneeId == member.id }
            MemberWorkload(
                memberId = member.id,
                estimatedMinutes = mine.sumOf { it.chore.effort.minutes },
                choreCount = mine.size,
                points = mine.sumOf { it.chore.points },
                averageDifficulty = mine.takeIf { it.isNotEmpty() }?.map { it.chore.difficulty }?.average()?.let { (it * 10).roundToInt() / 10.0 },
                undesirableCount = mine.count { member.preferences.forChore(it.chore).isUndesirable },
                completed = mine.count { it.state == OccurrenceState.COMPLETED },
                missed = mine.count { it.state == OccurrenceState.MISSED || it.state == OccurrenceState.OVERDUE },
                completedMinutes = occurrences.filter { it.state == OccurrenceState.COMPLETED && it.completedBy == member.id }
                    .sumOf { it.chore.effort.minutes },
            )
        }
        return HouseholdWorkload(
            members = workloads,
            fairShares = members.associate { it.id to if (totalShare == 0.0) 0.0 else it.share / totalShare },
            unassignedMinutes = occurrences.filter { it.assigneeId == null || it.assigneeId !in byId }.sumOf { it.chore.effort.minutes },
        )
    }
}
