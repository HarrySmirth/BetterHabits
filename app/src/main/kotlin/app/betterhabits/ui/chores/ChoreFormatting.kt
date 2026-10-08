package app.betterhabits.ui.chores

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import app.betterhabits.R
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Human-readable schedule, e.g. "Every Thursday at 19:00" or "Monthly on the last Friday". */
fun scheduleText(res: Resources, schedule: Schedule, locale: Locale): String {
    val r = schedule.recurrence
    val base = when (r) {
        Recurrence.Once -> res.getString(R.string.schedule_once, shortDate(schedule.startDate, locale))
        is Recurrence.Daily ->
            if (r.interval == 1) res.getString(R.string.schedule_daily)
            else res.getQuantityString(R.plurals.schedule_every_n_days, r.interval, r.interval)
        is Recurrence.Weekly -> {
            val days = weekdayList(r.days, locale)
            when {
                r.interval == 1 && r.days.size == 7 -> res.getString(R.string.schedule_daily)
                r.interval == 1 -> res.getString(R.string.schedule_weekly, days)
                else -> res.getQuantityString(R.plurals.schedule_every_n_weeks, r.interval, r.interval, days)
            }
        }
        is Recurrence.MonthlyOnDay ->
            if (r.interval == 1) res.getString(R.string.schedule_monthly_day, r.dayOfMonth)
            else res.getQuantityString(R.plurals.schedule_every_n_months_day, r.interval, r.interval, r.dayOfMonth)
        is Recurrence.MonthlyOnWeekday -> {
            val ordinal = res.getStringArray(R.array.week_ordinals)[if (r.ordinal == -1) 4 else r.ordinal - 1]
            val day = r.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            if (r.interval == 1) res.getString(R.string.schedule_monthly_weekday, ordinal, day)
            else res.getQuantityString(R.plurals.schedule_every_n_months_weekday, r.interval, r.interval, ordinal, day)
        }
        is Recurrence.Yearly -> {
            val date = LocalDate.of(2000, r.month, r.dayOfMonth).format(DateTimeFormatter.ofPattern("d MMM", locale))
            if (r.interval == 1) res.getString(R.string.schedule_yearly, date)
            else res.getQuantityString(R.plurals.schedule_every_n_years, r.interval, r.interval, date)
        }
    }
    return if (schedule.timesOfDay.isEmpty()) base
    else res.getString(R.string.schedule_at_times, base, schedule.timesOfDay.sorted().joinToString(", ") { timeText(it, locale) })
}

private fun weekdayList(days: Set<DayOfWeek>, locale: Locale): String {
    val sorted = days.sorted()
    val style = if (sorted.size == 1) TextStyle.FULL else TextStyle.SHORT
    return sorted.joinToString(", ") { it.getDisplayName(style, locale) }
}

fun shortDate(date: LocalDate, locale: Locale): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

fun timeText(time: LocalTime, locale: Locale): String = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** "5 min", "1 h", "1 h 30 min". */
fun effortText(res: Resources, effort: Effort): String = when {
    effort.hoursPart == 0 -> res.getString(R.string.effort_minutes, effort.minutesPart)
    effort.minutesPart == 0 -> res.getString(R.string.effort_hours, effort.hoursPart)
    else -> res.getString(R.string.effort_hours_minutes, effort.hoursPart, effort.minutesPart)
}

/**
 * "Today", "Tomorrow", "Yesterday" or a short date, plus the time for timed occurrences.
 * All-day occurrences are shown by their calendar date: converting their end-of-day due instant
 * to another timezone would shift them onto the wrong day. Timed ones are shown in [zone] (the device's).
 */
fun dueText(res: Resources, key: OccurrenceKey, dueAt: Instant, today: LocalDate, zone: ZoneId, locale: Locale): String {
    val local = dueAt.atZone(zone)
    val date = if (key.time == null) key.date else local.toLocalDate()
    val day = when (date) {
        today -> res.getString(R.string.date_today)
        today.plusDays(1) -> res.getString(R.string.date_tomorrow)
        today.minusDays(1) -> res.getString(R.string.date_yesterday)
        else -> shortDate(date, locale)
    }
    return if (key.time != null) res.getString(R.string.date_with_time, day, timeText(local.toLocalTime(), locale)) else day
}

fun ChoreCategory.labelRes(): Int = when (this) {
    ChoreCategory.KITCHEN -> R.string.category_kitchen
    ChoreCategory.BATHROOM -> R.string.category_bathroom
    ChoreCategory.LIVING_AREAS -> R.string.category_living_areas
    ChoreCategory.LAUNDRY -> R.string.category_laundry
    ChoreCategory.GARDEN -> R.string.category_garden
    ChoreCategory.HOUSEHOLD -> R.string.category_household
    ChoreCategory.OTHER -> R.string.category_other
}

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
@ReadOnlyComposable
fun resources(): Resources = LocalResources.current
