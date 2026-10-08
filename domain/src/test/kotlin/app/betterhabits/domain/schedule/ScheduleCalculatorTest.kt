package app.betterhabits.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.THURSDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ScheduleCalculatorTest {

    private val london = ZoneId.of("Europe/London")

    private fun schedule(recurrence: Recurrence, start: String, end: String? = null, times: List<String> = emptyList()) =
        Schedule(recurrence, LocalDate.parse(start), end?.let(LocalDate::parse), times.map(LocalTime::parse), london)

    private fun dates(s: Schedule, from: String, to: String) =
        ScheduleCalculator.occurrencesBetween(s, LocalDate.parse(from), LocalDate.parse(to)).map { it.key.date.toString() }

    @Test
    fun `one-off happens once on its start date`() {
        val s = schedule(Recurrence.Once, "2026-10-10")
        assertEquals(listOf("2026-10-10"), dates(s, "2026-10-01", "2026-12-31"))
    }

    @Test
    fun `take bins out every Thursday`() {
        val s = schedule(Recurrence.Weekly(setOf(THURSDAY)), "2026-10-05")
        assertEquals(listOf("2026-10-08", "2026-10-15", "2026-10-22", "2026-10-29"), dates(s, "2026-10-01", "2026-10-31"))
    }

    @Test
    fun `fortnightly on several days counts weeks from the start week`() {
        val s = schedule(Recurrence.Weekly(setOf(MONDAY, FRIDAY), interval = 2), "2026-10-07") // a Wednesday
        assertEquals(
            listOf("2026-10-09", "2026-10-19", "2026-10-23", "2026-11-02"),
            dates(s, "2026-10-01", "2026-11-03"),
        )
    }

    @Test
    fun `water plants every 3 days`() {
        val s = schedule(Recurrence.Daily(interval = 3), "2026-10-08")
        assertEquals(listOf("2026-10-08", "2026-10-11", "2026-10-14"), dates(s, "2026-10-01", "2026-10-16"))
    }

    @Test
    fun `monthly on the 31st clamps to the last day of shorter months`() {
        val s = schedule(Recurrence.MonthlyOnDay(31), "2026-01-31")
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), dates(s, "2026-01-01", "2026-04-30"))
    }

    @Test
    fun `monthly on the first Saturday and the last Friday`() {
        val first = schedule(Recurrence.MonthlyOnWeekday(1, SATURDAY), "2026-10-01")
        assertEquals(listOf("2026-10-03", "2026-11-07", "2026-12-05"), dates(first, "2026-10-01", "2026-12-31"))
        val last = schedule(Recurrence.MonthlyOnWeekday(-1, FRIDAY), "2026-10-01")
        assertEquals(listOf("2026-10-30", "2026-11-27"), dates(last, "2026-10-01", "2026-11-30"))
    }

    @Test
    fun `every other month`() {
        val s = schedule(Recurrence.MonthlyOnDay(15, interval = 2), "2026-10-15")
        assertEquals(listOf("2026-10-15", "2026-12-15", "2027-02-15"), dates(s, "2026-10-01", "2027-03-31"))
    }

    @Test
    fun `yearly on 29 February falls on the 28th in non-leap years`() {
        val s = schedule(Recurrence.Yearly(2, 29), "2028-01-01")
        assertEquals(listOf("2028-02-29", "2029-02-28"), dates(s, "2028-01-01", "2029-12-31"))
    }

    @Test
    fun `start and end dates bound the schedule`() {
        val s = schedule(Recurrence.Daily(), "2026-10-10", end = "2026-10-12")
        assertEquals(listOf("2026-10-10", "2026-10-11", "2026-10-12"), dates(s, "2026-10-01", "2026-10-31"))
    }

    @Test
    fun `multiple times a day produce one occurrence per slot in order`() {
        val s = schedule(Recurrence.Daily(), "2026-10-08", times = listOf("19:00", "08:00"))
        val occurrences = ScheduleCalculator.occurrencesBetween(s, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08"))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(19, 0)), occurrences.map { it.key.time })
        assertEquals(Instant.parse("2026-10-08T07:00:00Z"), occurrences.first().dueAt) // BST = UTC+1
    }

    @Test
    fun `all-day occurrences are due at the end of the local day`() {
        val s = schedule(Recurrence.Once, "2026-10-08")
        val due = ScheduleCalculator.occurrencesBetween(s, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08")).single().dueAt
        assertEquals(Instant.parse("2026-10-08T22:59:59Z"), due)
    }

    @Test
    fun `daylight saving changes keep local wall-clock times`() {
        // UK clocks go back on 25 Oct 2026 (BST -> GMT) and forward on 28 Mar 2027.
        val s = schedule(Recurrence.Daily(), "2026-10-24", times = listOf("09:00"))
        val due = ScheduleCalculator.occurrencesBetween(s, LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25")).map { it.dueAt }
        assertEquals(listOf(Instant.parse("2026-10-24T08:00:00Z"), Instant.parse("2026-10-25T09:00:00Z")), due)

        // 01:30 doesn't exist on 28 Mar 2027 in London; it is shifted forward, not dropped.
        val gap = schedule(Recurrence.Once, "2027-03-28", times = listOf("01:30"))
        assertEquals(
            Instant.parse("2027-03-28T01:30:00Z"),
            ScheduleCalculator.occurrencesBetween(gap, LocalDate.parse("2027-03-28"), LocalDate.parse("2027-03-28")).single().dueAt,
        )
    }

    @Test
    fun `next occurrence after a moment`() {
        val s = schedule(Recurrence.Weekly(setOf(THURSDAY)), "2026-10-01", times = listOf("19:00"))
        val afterDue = Instant.parse("2026-10-08T18:30:00Z") // Thursday 19:30 BST, already past
        assertEquals(LocalDate.parse("2026-10-15"), ScheduleCalculator.nextOccurrence(s, afterDue)?.key?.date)
        assertNull(ScheduleCalculator.nextOccurrence(schedule(Recurrence.Once, "2026-10-01"), afterDue))
    }

    @Test
    fun `occursOn respects interval anchoring`() {
        val s = schedule(Recurrence.Daily(interval = 7), "2026-10-08")
        assertTrue(ScheduleCalculator.occursOn(s, LocalDate.parse("2026-10-15")))
        assertFalse(ScheduleCalculator.occursOn(s, LocalDate.parse("2026-10-14")))
        assertFalse(ScheduleCalculator.occursOn(s, LocalDate.parse("2026-10-01")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `weekly without days is rejected`() {
        schedule(Recurrence.Weekly(emptySet()), "2026-10-08")
    }
}
