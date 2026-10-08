package app.betterhabits.domain.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How often something repeats. Interval = "every N units" (1 = every unit).
 * Mirrors the recurrence_* columns of public.chores (see supabase/migrations).
 */
sealed interface Recurrence {
    val interval: Int

    /** A single occurrence on the schedule's start date. */
    data object Once : Recurrence {
        override val interval: Int = 1
    }

    /** Every [interval] days, counted from the start date. */
    data class Daily(override val interval: Int = 1) : Recurrence

    /** On [days] of every [interval]-th week (weeks start on Monday, counted from the start date's week). */
    data class Weekly(val days: Set<DayOfWeek>, override val interval: Int = 1) : Recurrence

    /** On [dayOfMonth] every [interval] months; clamped to the last day in shorter months. */
    data class MonthlyOnDay(val dayOfMonth: Int, override val interval: Int = 1) : Recurrence

    /** On the [ordinal]-th [dayOfWeek] of the month (1..4, or -1 for the last) every [interval] months. */
    data class MonthlyOnWeekday(val ordinal: Int, val dayOfWeek: DayOfWeek, override val interval: Int = 1) : Recurrence

    /** On [month]/[dayOfMonth] every [interval] years; 29 Feb falls on 28 Feb in non-leap years. */
    data class Yearly(val month: Int, val dayOfMonth: Int, override val interval: Int = 1) : Recurrence

    fun validate() {
        require(interval in 1..MAX_INTERVAL) { "interval must be 1..$MAX_INTERVAL" }
        when (this) {
            is Weekly -> require(days.isNotEmpty()) { "weekly recurrence needs at least one day" }
            is MonthlyOnDay -> require(dayOfMonth in 1..31) { "dayOfMonth must be 1..31" }
            is MonthlyOnWeekday -> require(ordinal in 1..4 || ordinal == -1) { "ordinal must be 1..4 or -1" }
            is Yearly -> {
                require(month in 1..12) { "month must be 1..12" }
                require(dayOfMonth in 1..31) { "dayOfMonth must be 1..31" }
            }
            Once, is Daily -> Unit
        }
    }

    companion object {
        const val MAX_INTERVAL = 365
    }
}

/**
 * When a chore happens. Dates and times are local to [zone] (the household's timezone); an empty
 * [timesOfDay] means "any time that day" (due by the end of the day).
 */
data class Schedule(
    val recurrence: Recurrence,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val timesOfDay: List<LocalTime> = emptyList(),
    val zone: ZoneId,
) {
    init {
        recurrence.validate()
        require(endDate == null || !endDate.isBefore(startDate)) { "endDate must not be before startDate" }
    }

    val isRepeating: Boolean get() = recurrence != Recurrence.Once
}
