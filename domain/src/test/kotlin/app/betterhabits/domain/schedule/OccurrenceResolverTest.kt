package app.betterhabits.domain.schedule

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.model.OccurrenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OccurrenceResolverTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.parse("2026-10-08")
    private val now = Instant.parse("2026-10-08T11:00:00Z") // 12:00 BST

    private fun chore(id: String, recurrence: Recurrence, start: String = "2026-10-01", assignee: String? = "harry") = Chore(
        id = id,
        householdId = "h",
        name = id,
        effort = Effort(10),
        schedule = Schedule(recurrence, LocalDate.parse(start), zone = zone),
        assigneeId = assignee,
    )

    @Test
    fun `an all-day occurrence becomes missed once the next one's day starts`() {
        val daily = chore("dishes", Recurrence.Daily())
        val occurrences = OccurrenceResolver.resolve(listOf(daily), emptyList(), LocalDate.parse("2026-10-05"), today, now)
        val states = occurrences.associate { it.key.date.toString() to it.state }
        assertEquals(OccurrenceState.MISSED, states["2026-10-06"])
        assertEquals(OccurrenceState.MISSED, states["2026-10-07"]) // today's dishes replace yesterday's
        assertEquals(OccurrenceState.UPCOMING, states["2026-10-08"]) // due end of today
    }

    @Test
    fun `a weekly chore stays overdue until next week's occurrence`() {
        val weekly = chore("bathroom", Recurrence.Weekly(setOf(DayOfWeek.MONDAY)))
        val monday = OccurrenceResolver.resolve(listOf(weekly), emptyList(), LocalDate.parse("2026-10-05"), today, now).single()
        assertEquals(OccurrenceState.OVERDUE, monday.state)
    }

    @Test
    fun `a missed timed slot stays overdue until the next slot is due`() {
        val feed = chore("feed cat", Recurrence.Daily()).let {
            it.copy(schedule = it.schedule.copy(timesOfDay = listOf(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(19, 0))))
        }
        val states = OccurrenceResolver.resolve(listOf(feed), emptyList(), today, today, now).map { it.state }
        assertEquals(listOf(OccurrenceState.OVERDUE, OccurrenceState.UPCOMING), states) // 12:00 BST

        val evening = Instant.parse("2026-10-08T18:30:00Z") // 19:30 BST
        val later = OccurrenceResolver.resolve(listOf(feed), emptyList(), today, today, evening).map { it.state }
        assertEquals(listOf(OccurrenceState.MISSED, OccurrenceState.OVERDUE), later)
    }

    @Test
    fun `a timed occurrence from an earlier day is superseded when the next one's day starts`() {
        // Vacuum Sundays and Thursdays at 18:00; on Thursday at 12:00 Sunday's is no longer overdue.
        val vacuum = chore("vacuum", Recurrence.Weekly(setOf(DayOfWeek.SUNDAY, DayOfWeek.THURSDAY))).let {
            it.copy(schedule = it.schedule.copy(timesOfDay = listOf(java.time.LocalTime.of(18, 0))))
        }
        val agenda = OccurrenceResolver.agenda(listOf(vacuum), emptyList(), today, now)
        assertEquals(listOf("2026-10-08:UPCOMING"), agenda.map { "${it.key.date}:${it.state}" })
    }

    @Test
    fun `records mark occurrences done and track who did it`() {
        val bins = chore("bins", Recurrence.Weekly(setOf(DayOfWeek.THURSDAY)))
        val done = OccurrenceRecord(
            "bins", OccurrenceKey(today), OccurrenceStatus.COMPLETED,
            completedBy = "sarah", completedAt = now,
        )
        val occurrence = OccurrenceResolver.resolve(listOf(bins), listOf(done), today, today, now).single()
        assertEquals(OccurrenceState.COMPLETED, occurrence.state)
        assertEquals("harry", occurrence.assigneeId)
        assertEquals("sarah", occurrence.completedBy)
    }

    @Test
    fun `occurrence assignee override beats chore default`() {
        val bins = chore("bins", Recurrence.Daily())
        val reassigned = OccurrenceRecord("bins", OccurrenceKey(today), OccurrenceStatus.PENDING, assigneeId = "sarah")
        assertEquals("sarah", OccurrenceResolver.resolve(listOf(bins), listOf(reassigned), today, today, now).single().assigneeId)
    }

    @Test
    fun `snoozed occurrences leave the agenda until the snooze ends`() {
        val yesterday = today.minusDays(1)
        val chore = chore("vacuum", Recurrence.Once, start = yesterday.toString())
        val snoozed = OccurrenceRecord("vacuum", OccurrenceKey(yesterday), OccurrenceStatus.PENDING, snoozedUntil = now.plusSeconds(3600))
        assertEquals(emptyList<Any>(), OccurrenceResolver.agenda(listOf(chore), listOf(snoozed), today, now))

        val later = now.plusSeconds(7200)
        assertEquals(OccurrenceState.OVERDUE, OccurrenceResolver.agenda(listOf(chore), listOf(snoozed), today, later).single().state)
    }

    @Test
    fun `agenda shows overdue, today's pending and today's done items only`() {
        val weekly = chore("bathroom", Recurrence.Weekly(setOf(DayOfWeek.MONDAY))) // last due Mon 5 Oct
        val daily = chore("dishes", Recurrence.Daily())
        val doneToday = OccurrenceRecord("dishes", OccurrenceKey(today), OccurrenceStatus.COMPLETED, completedBy = "harry", completedAt = now)
        val agenda = OccurrenceResolver.agenda(listOf(weekly, daily), listOf(doneToday), today, now)
        assertEquals(
            listOf("bathroom@2026-10-05:OVERDUE", "dishes@2026-10-08:COMPLETED"),
            agenda.map { "${it.chore.id}@${it.key.date}:${it.state}" },
        )
    }

    @Test
    fun `inactive chores are ignored`() {
        val paused = chore("paused", Recurrence.Daily()).copy(active = false)
        assertEquals(emptyList<Any>(), OccurrenceResolver.resolve(listOf(paused), emptyList(), today, today, now))
    }
}
