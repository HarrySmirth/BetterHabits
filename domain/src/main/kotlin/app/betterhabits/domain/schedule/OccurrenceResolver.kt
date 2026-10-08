package app.betterhabits.domain.schedule

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreOccurrence
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.model.OccurrenceStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Merges computed schedules with stored [OccurrenceRecord]s to produce what the user sees.
 *
 * Overdue vs missed: a pending, past-due occurrence stays OVERDUE until the chore's next
 * occurrence becomes current, then it is MISSED. The next occurrence becomes current when its
 * day starts, or, for another slot on the same day, when its due time passes. So yesterday's
 * undone dishes don't sit next to today's, but a missed 08:00 slot stays overdue until 19:00.
 */
object OccurrenceResolver {

    fun resolve(
        chores: List<Chore>,
        records: List<OccurrenceRecord>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
    ): List<ChoreOccurrence> {
        val recordsByChore = records.groupBy { it.choreId }
        return chores.filter { it.active }.flatMap { chore ->
            val choreRecords = recordsByChore[chore.id].orEmpty().associateBy { it.key }
            ScheduleCalculator.occurrencesBetween(chore.schedule, from, to).map { occurrence ->
                val record = choreRecords[occurrence.key]
                ChoreOccurrence(
                    chore = chore,
                    key = occurrence.key,
                    dueAt = occurrence.dueAt,
                    record = record,
                    state = stateOf(chore, occurrence, record, now),
                )
            }
        }.sortedWith(compareBy({ it.effectiveDueAt }, { it.chore.name.lowercase() }))
    }

    private fun stateOf(chore: Chore, occurrence: ScheduledOccurrence, record: OccurrenceRecord?, now: Instant): OccurrenceState =
        when (record?.status) {
            OccurrenceStatus.COMPLETED -> OccurrenceState.COMPLETED
            OccurrenceStatus.SKIPPED -> OccurrenceState.SKIPPED
            else -> when {
                record?.snoozedUntil?.isAfter(now) == true -> OccurrenceState.SNOOZED
                occurrence.dueAt.isAfter(now) -> OccurrenceState.UPCOMING
                isSuperseded(chore, occurrence, now) -> OccurrenceState.MISSED
                else -> OccurrenceState.OVERDUE
            }
        }

    private fun isSuperseded(chore: Chore, occurrence: ScheduledOccurrence, now: Instant): Boolean {
        val next = ScheduleCalculator.nextOccurrence(chore.schedule, occurrence.dueAt) ?: return false
        if (!next.dueAt.isAfter(now)) return true
        // A later day's occurrence takes over as soon as that day starts; same-day slots wait for their time.
        val today = now.atZone(chore.schedule.zone).toLocalDate()
        return next.key.date.isAfter(occurrence.key.date) && !next.key.date.isAfter(today)
    }

    /**
     * The to-do list for [today]: overdue items (within [lookbackDays]), today's pending items, and
     * snoozed items whose snooze has ended, plus today's done items so progress is visible.
     */
    fun agenda(
        chores: List<Chore>,
        records: List<OccurrenceRecord>,
        today: LocalDate,
        now: Instant,
        lookbackDays: Long = 7,
    ): List<ChoreOccurrence> =
        resolve(chores, records, today.minusDays(lookbackDays), today, now).filter { occurrence ->
            when (occurrence.state) {
                OccurrenceState.OVERDUE -> true
                OccurrenceState.MISSED -> false
                OccurrenceState.SNOOZED -> false
                OccurrenceState.UPCOMING, OccurrenceState.COMPLETED, OccurrenceState.SKIPPED -> occurrence.key.date == today
            }
        }
}
