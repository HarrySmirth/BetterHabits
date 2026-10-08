package app.betterhabits.domain.model

import app.betterhabits.domain.allocation.MemberPreferences
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChoreTemplateTest {

    private val thursday = LocalDate.parse("2026-10-08")
    private val deepClean = ChoreTemplate(
        id = "builtin.bathroom.deep_clean",
        scope = TemplateScope.BUILT_IN,
        name = "Bathroom deep clean",
        category = ChoreCategory.BATHROOM,
        effort = Effort(45),
        difficulty = 4,
        repeatKind = RepeatKind.WEEKLY,
        repeatInterval = 2,
        checklist = listOf("Toilet", "Sink", "Shower", "Mirror", "Mop floor"),
    )

    @Test
    fun `a template becomes an unassigned chore on the chosen start day`() {
        val chore = deepClean.toChore("c1", "h1", thursday, ZoneOffset.UTC)
        assertEquals("Bathroom deep clean", chore.name)
        assertEquals(Recurrence.Weekly(setOf(DayOfWeek.THURSDAY), 2), chore.schedule.recurrence)
        assertEquals(thursday, chore.schedule.startDate)
        assertEquals(5, chore.checklist.size)
        assertEquals("builtin.bathroom.deep_clean", chore.templateId)
        assertEquals(null, chore.assigneeId)
    }

    @Test
    fun `each repeat kind maps to a schedule`() {
        fun kind(k: RepeatKind) = deepClean.copy(repeatKind = k, repeatInterval = 1).recurrence(thursday)
        assertEquals(Recurrence.Once, kind(RepeatKind.ONCE))
        assertEquals(Recurrence.Daily(1), kind(RepeatKind.DAILY))
        assertEquals(Recurrence.MonthlyOnDay(8, 1), kind(RepeatKind.MONTHLY))
    }

    @Test
    fun `saving a chore as a template keeps its definition but not its dates or assignee`() {
        val chore = deepClean.toChore("c1", "h1", thursday, ZoneOffset.UTC).copy(assigneeId = "sarah", effort = Effort(50))
        val t = ChoreTemplate.fromChore(chore, "t1", TemplateScope.PERSONAL, householdId = null, ownerId = "harry")
        assertEquals(50, t.effort.minutes)
        assertEquals(RepeatKind.WEEKLY, t.repeatKind)
        assertEquals(2, t.repeatInterval)
        assertEquals(chore.checklist, t.checklist)
    }

    @Test
    fun `scope and owner must agree`() {
        assertThrows(IllegalArgumentException::class.java) { deepClean.copy(scope = TemplateScope.HOUSEHOLD) }
        assertThrows(IllegalArgumentException::class.java) { deepClean.copy(scope = TemplateScope.PERSONAL, ownerId = null) }
        assertThrows(IllegalArgumentException::class.java) { deepClean.copy(checklist = List(31) { "Step $it" }) }
    }

    @Test
    fun `template preferences apply to chores made from the template, below chore preferences`() {
        val chore = deepClean.toChore("c1", "h1", thursday, ZoneOffset.UTC)
        val prefs = MemberPreferences(
            "harry",
            byCategory = mapOf(ChoreCategory.BATHROOM to PreferenceLevel.LIKE),
            byTemplate = mapOf(deepClean.id to PreferenceLevel.HATE),
        )
        assertEquals(PreferenceLevel.HATE, prefs.forChore(chore))
        assertEquals(PreferenceLevel.LOVE, prefs.copy(byChore = mapOf("c1" to PreferenceLevel.LOVE)).forChore(chore))
        assertEquals(PreferenceLevel.LIKE, prefs.forChore(chore.copy(templateId = null)))
    }

    @Test
    fun `step progress ignores steps that no longer exist`() {
        val chore = Chore("c1", "h1", "Bins", effort = Effort(5), checklist = listOf("Kitchen", "Recycling"),
            schedule = Schedule(Recurrence.Daily(), thursday, zone = ZoneOffset.UTC))
        fun occurrence(steps: Set<Int>) = ChoreOccurrence(
            chore, OccurrenceKey(thursday, null), Instant.EPOCH,
            OccurrenceRecord("c1", OccurrenceKey(thursday, null), OccurrenceStatus.PENDING, checkedSteps = steps),
            OccurrenceState.UPCOMING,
        )
        assertEquals(setOf(0), occurrence(setOf(0, 5)).checkedSteps)
        assertFalse(occurrence(setOf(0, 5)).allStepsChecked)
        assertTrue(occurrence(setOf(0, 1)).allStepsChecked)
    }
}
