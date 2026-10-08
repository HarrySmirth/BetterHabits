package app.betterhabits.domain.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Identifies one occurrence of a schedule: a local date plus the due time slot (null = all day). */
data class OccurrenceKey(val date: LocalDate, val time: LocalTime? = null) : Comparable<OccurrenceKey> {
    override fun compareTo(other: OccurrenceKey): Int =
        compareValuesBy(this, other, { it.date }, { it.time ?: LocalTime.MAX })
}

data class ScheduledOccurrence(
    val key: OccurrenceKey,
    /** When it is due: the slot time, or the end of the day for all-day occurrences. */
    val dueAt: Instant,
)

/**
 * Pure, timezone-aware expansion of a [Schedule] into concrete occurrences. Occurrences are never
 * stored up front: they are computed on demand for whatever window the UI needs.
 */
object ScheduleCalculator {

    /** Whether [date] (local to the schedule's zone) has occurrences. */
    fun occursOn(schedule: Schedule, date: LocalDate): Boolean {
        if (date.isBefore(schedule.startDate)) return false
        if (schedule.endDate != null && date.isAfter(schedule.endDate)) return false
        val start = schedule.startDate
        return when (val r = schedule.recurrence) {
            Recurrence.Once -> date == start
            is Recurrence.Daily -> ChronoUnit.DAYS.between(start, date) % r.interval == 0L
            is Recurrence.Weekly -> date.dayOfWeek in r.days &&
                ChronoUnit.WEEKS.between(start.mondayOfWeek(), date.mondayOfWeek()) % r.interval == 0L
            is Recurrence.MonthlyOnDay -> monthMatches(start, date, r.interval) &&
                date.dayOfMonth == minOf(r.dayOfMonth, date.lengthOfMonth())
            is Recurrence.MonthlyOnWeekday -> monthMatches(start, date, r.interval) &&
                date == nthWeekdayOfMonth(YearMonth.from(date), r.ordinal, r.dayOfWeek)
            is Recurrence.Yearly -> date.monthValue == r.month &&
                (date.year - start.year) % r.interval == 0 &&
                date.dayOfMonth == minOf(r.dayOfMonth, date.lengthOfMonth())
        }
    }

    /** All occurrences with dates in [from]..[to] (inclusive), in chronological order. */
    fun occurrencesBetween(schedule: Schedule, from: LocalDate, to: LocalDate): List<ScheduledOccurrence> {
        val first = maxOf(from, schedule.startDate)
        val last = schedule.endDate?.let { minOf(it, to) } ?: to
        if (last.isBefore(first)) return emptyList()
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .filter { occursOn(schedule, it) }
            .flatMap { date -> occurrencesOn(schedule, date).asSequence() }
            .toList()
    }

    /** The first occurrence due strictly after [after], or null if the schedule has ended. */
    fun nextOccurrence(schedule: Schedule, after: Instant, searchLimitDays: Long = 366L * 5): ScheduledOccurrence? {
        val startDate = maxOf(after.atZone(schedule.zone).toLocalDate(), schedule.startDate)
        return generateSequence(startDate) { it.plusDays(1) }
            .take(searchLimitDays.toInt())
            .takeWhile { schedule.endDate == null || !it.isAfter(schedule.endDate) }
            .filter { occursOn(schedule, it) }
            .flatMap { occurrencesOn(schedule, it).asSequence() }
            .firstOrNull { it.dueAt.isAfter(after) }
    }

    /** The occurrences on a matching [date], one per time slot (or a single all-day one). */
    fun occurrencesOn(schedule: Schedule, date: LocalDate): List<ScheduledOccurrence> =
        if (schedule.timesOfDay.isEmpty()) {
            listOf(ScheduledOccurrence(OccurrenceKey(date), endOfDay(date, schedule)))
        } else {
            schedule.timesOfDay.sorted().distinct().map { time ->
                // ZonedDateTime.of shifts times that fall in a DST gap forward, so nothing is lost.
                ScheduledOccurrence(OccurrenceKey(date, time), ZonedDateTime.of(date, time, schedule.zone).toInstant())
            }
        }

    /** Due instant for an occurrence key under [schedule]'s zone. */
    fun dueAt(schedule: Schedule, key: OccurrenceKey): Instant =
        key.time?.let { ZonedDateTime.of(key.date, it, schedule.zone).toInstant() } ?: endOfDay(key.date, schedule)

    private fun endOfDay(date: LocalDate, schedule: Schedule): Instant =
        date.plusDays(1).atStartOfDay(schedule.zone).toInstant().minusSeconds(1)

    private fun LocalDate.mondayOfWeek(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun monthMatches(start: LocalDate, date: LocalDate, interval: Int): Boolean =
        ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(date)) % interval == 0L

    private fun nthWeekdayOfMonth(month: YearMonth, ordinal: Int, dayOfWeek: DayOfWeek): LocalDate? =
        if (ordinal == -1) {
            month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(dayOfWeek))
        } else {
            month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dayOfWeek)).takeIf { YearMonth.from(it) == month }
        }
}
