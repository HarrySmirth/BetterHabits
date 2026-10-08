package app.betterhabits.domain.allocation

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WorkloadCalculatorTest {

    private val from = LocalDate.parse("2026-10-05") // Monday
    private val to = from.plusDays(6)
    private val now = Instant.parse("2026-10-08T12:00:00Z") // Thursday

    private fun daily(id: String, minutes: Int, assignee: String?, difficulty: Int = 3, points: Int = 0) =
        Chore(id, "h", id, effort = Effort(minutes), difficulty = difficulty, points = points, assigneeId = assignee,
            schedule = Schedule(Recurrence.Daily(), from, zone = ZoneOffset.UTC))

    private val harry = AllocationMember("harry", "Harry", preferences = MemberPreferences("harry", byChore = mapOf("dishes" to PreferenceLevel.HATE)))
    private val sarah = AllocationMember("sarah", "Sarah")

    @Test
    fun `effort, not chore count, measures workload`() {
        val chores = listOf(daily("dishes", 5, "harry"), daily("vacuum", 45, "sarah"))
        val w = WorkloadCalculator.calculate(chores, emptyList(), listOf(harry, sarah), from, to, now)
        val h = w.members.first { it.memberId == "harry" }
        val s = w.members.first { it.memberId == "sarah" }
        assertEquals(7, h.choreCount)
        assertEquals(7, s.choreCount)
        assertEquals(35, h.estimatedMinutes)
        assertEquals(315, s.estimatedMinutes)
        assertEquals(0.1, w.actualShare("harry"), 1e-9)
        assertEquals(7, h.undesirableCount)
        assertTrue(w.balanceScore < 0.7)
    }

    @Test
    fun `completion rate counts completed vs missed among their due chores, and who actually did the work`() {
        val chores = listOf(daily("dishes", 10, "harry"))
        val records = listOf(
            OccurrenceRecord("dishes", OccurrenceKey(from), OccurrenceStatus.COMPLETED, completedBy = "harry", completedAt = now),
            OccurrenceRecord("dishes", OccurrenceKey(from.plusDays(1)), OccurrenceStatus.COMPLETED, completedBy = "sarah", completedAt = now),
        )
        val w = WorkloadCalculator.calculate(chores, records, listOf(harry, sarah), from, to, now)
        val h = w.members.first { it.memberId == "harry" }
        assertEquals(2, h.completed)
        assertEquals(1, h.missed) // the 7th, superseded by the 8th; today's is still upcoming
        assertEquals(2.0 / 3, h.completionRate!!, 1e-9)
        assertEquals(10, h.completedMinutes)
        assertEquals(10, w.members.first { it.memberId == "sarah" }.completedMinutes)
    }

    @Test
    fun `fair shares and the balance score account for share weights`() {
        val child = AllocationMember("kid", "Kid", share = 0.5)
        val chores = listOf(daily("a", 20, "harry"), daily("b", 10, "kid"))
        val w = WorkloadCalculator.calculate(chores, emptyList(), listOf(harry, child), from, to, now)
        assertEquals(2.0 / 3, w.fairShares.getValue("harry"), 1e-9)
        assertEquals(1.0, w.balanceScore, 1e-9)
    }

    @Test
    fun `unassigned work is reported separately and empty households are balanced`() {
        val w = WorkloadCalculator.calculate(listOf(daily("x", 10, null)), emptyList(), listOf(harry), from, to, now)
        assertEquals(70, w.unassignedMinutes)
        assertEquals(1.0, w.balanceScore, 1e-9)
        assertNull(w.members.single().averageDifficulty)
    }
}
