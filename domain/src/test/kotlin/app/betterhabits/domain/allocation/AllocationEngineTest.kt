package app.betterhabits.domain.allocation

import app.betterhabits.domain.allocation.PreferenceLevel.CANNOT_DO
import app.betterhabits.domain.allocation.PreferenceLevel.DISLIKE
import app.betterhabits.domain.allocation.PreferenceLevel.HATE
import app.betterhabits.domain.allocation.PreferenceLevel.LIKE
import app.betterhabits.domain.allocation.PreferenceLevel.LOVE
import app.betterhabits.domain.allocation.PreferenceLevel.NEUTRAL
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs

class AllocationEngineTest {

    private val monday = LocalDate.parse("2026-10-05")

    private fun weekly(id: String, minutes: Int, day: DayOfWeek = DayOfWeek.SATURDAY, category: ChoreCategory = ChoreCategory.OTHER, time: LocalTime? = null) =
        Chore(id, "h", id, category = category, effort = Effort(minutes),
            schedule = Schedule(Recurrence.Weekly(setOf(day)), monday, timesOfDay = listOfNotNull(time), zone = ZoneOffset.UTC))

    private fun daily(id: String, minutes: Int) =
        Chore(id, "h", id, effort = Effort(minutes), schedule = Schedule(Recurrence.Daily(), monday, zone = ZoneOffset.UTC))

    private fun member(id: String, vararg prefs: Pair<String, PreferenceLevel>, share: Double = 1.0, availability: Availability = Availability(id)) =
        AllocationMember(id, id, share, MemberPreferences(id, byChore = prefs.toMap()), availability)

    private fun allocate(chores: List<Chore>, members: List<AllocationMember>, settings: AllocationSettings = AllocationSettings(), recent: Map<String, List<String>> = emptyMap(), history: Map<String, Int> = emptyMap()) =
        AllocationEngine.allocate(AllocationRequest(chores, members, settings, monday, recentAssignees = recent, recentCompletedMinutes = history))

    private fun AllocationResult.assignee(choreId: String) = proposals.first { it.choreId == choreId }.assigneeId
    private fun AllocationResult.reasons(choreId: String) = proposals.first { it.choreId == choreId }.reasons
    private fun AllocationResult.load(memberId: String) = projected.first { it.memberId == memberId }.minutesPerWeek

    /** The example household from the product brief. */
    private val brief = listOf(
        weekly("vacuum", 45), weekly("bathroom", 40), daily("dishwasher", 5),
        weekly("bins", 10, DayOfWeek.THURSDAY), weekly("laundry", 30), weekly("bedding", 20, DayOfWeek.SUNDAY),
    )
    private val harry = member("harry", "vacuum" to LIKE, "dishwasher" to NEUTRAL, "bathroom" to HATE)
    private val sarah = member("sarah", "vacuum" to DISLIKE, "bathroom" to LIKE)

    @Test
    fun `the brief's household gets vacuuming to Harry, the bathroom to Sarah, and balanced totals`() {
        val result = allocate(brief, listOf(harry, sarah))
        assertEquals("harry", result.assignee("vacuum"))
        assertEquals("sarah", result.assignee("bathroom"))
        val h = result.load("harry")
        val s = result.load("sarah")
        assertTrue("loads $h vs $s should be within 20 minutes", abs(h - s) <= 20)
        assertTrue(result.reasons("bathroom").contains(AllocationReason.Prefers(LIKE)))
    }

    @Test
    fun `with preferences turned off it is purely about balancing minutes`() {
        val result = allocate(listOf(weekly("a", 30), weekly("b", 30)), listOf(member("harry", "a" to LOVE, "b" to LOVE), member("sarah")), AllocationSettings(preferenceWeight = 0))
        assertEquals(setOf("harry", "sarah"), setOf(result.assignee("a"), result.assignee("b")))
        assertFalse(result.proposals.flatMap { it.reasons }.any { it is AllocationReason.Prefers })
    }

    @Test
    fun `can't-do and exclusions are hard constraints`() {
        val chores = listOf(weekly("mow", 60).copy(excludedMemberIds = setOf("sarah")), weekly("ladder", 20))
        val result = allocate(chores, listOf(member("harry", "ladder" to CANNOT_DO), member("sarah")))
        assertEquals("harry", result.assignee("mow"))
        assertEquals("sarah", result.assignee("ladder"))
        assertEquals(listOf(AllocationReason.OnlyOneEligible), result.reasons("mow"))
    }

    @Test
    fun `nobody eligible leaves the chore unassigned and says why`() {
        val result = allocate(listOf(weekly("x", 10)), listOf(member("harry", "x" to CANNOT_DO)))
        assertNull(result.assignee("x"))
        assertEquals(listOf(AllocationReason.NoOneEligible), result.reasons("x"))
    }

    @Test
    fun `locked assignments are kept and their load counts`() {
        val locked = weekly("big", 120).copy(assigneeId = "harry", assignmentLocked = true)
        val result = allocate(listOf(locked, weekly("a", 30), weekly("b", 30)), listOf(member("harry"), member("sarah")))
        assertEquals("harry", result.assignee("big"))
        assertEquals(listOf(AllocationReason.Locked), result.reasons("big"))
        assertEquals("sarah", result.assignee("a"))
        assertEquals("sarah", result.assignee("b"))
    }

    @Test
    fun `hating everything doesn't let someone avoid all the work`() {
        val chores = (1..6).map { weekly("c$it", 30) }
        val hater = member("harry", *chores.map { it.id to HATE }.toTypedArray())
        val result = allocate(chores, listOf(hater, member("sarah")), AllocationSettings(preferenceWeight = 100))
        val h = result.load("harry")
        val s = result.load("sarah")
        assertTrue("harry still takes a fair part ($h vs $s)", h >= 60 && abs(h - s) <= 60)
    }

    @Test
    fun `disliked chores are shared out when avoidance isn't allowed`() {
        val chores = listOf(weekly("toilet", 20), weekly("oven", 20), weekly("easy1", 20), weekly("easy2", 20))
        val members = listOf(member("harry", "toilet" to DISLIKE, "oven" to DISLIKE), member("sarah", "toilet" to DISLIKE, "oven" to DISLIKE))
        val result = allocate(chores, members)
        assertTrue(result.projected.all { it.undesirableMinutesPerWeek == 20 })
        assertTrue(result.proposals.any { AllocationReason.SharesUndesirableWork in it.reasons })
    }

    @Test
    fun `someone away for the whole window can't be picked, partial absence counts against them`() {
        val away = Availability("harry", awayPeriods = listOf(DateRange(monday, monday.plusDays(40))))
        assertEquals("sarah", allocate(listOf(weekly("x", 30)), listOf(member("harry", availability = away), member("sarah"))).assignee("x"))

        val notSaturdays = Availability("sarah", unavailableDays = setOf(DayOfWeek.SATURDAY))
        val result = allocate(listOf(weekly("sat", 30)), listOf(member("harry"), member("sarah", availability = notSaturdays)))
        assertEquals("harry", result.assignee("sat"))
    }

    @Test
    fun `evening people get evening chores when it's otherwise even`() {
        val evenings = Availability("harry", preferredTimes = setOf(TimeOfDay.EVENING))
        val mornings = Availability("sarah", preferredTimes = setOf(TimeOfDay.MORNING))
        val result = allocate(
            listOf(weekly("evening", 20, time = LocalTime.of(19, 0)), weekly("morning", 20, time = LocalTime.of(8, 0))),
            listOf(member("harry", availability = evenings), member("sarah", availability = mornings)),
        )
        assertEquals("harry", result.assignee("evening"))
        assertEquals("sarah", result.assignee("morning"))
        assertTrue(AllocationReason.SuitsTheirTime in result.reasons("evening"))
    }

    @Test
    fun `rotating chores go to whoever didn't do it last, if that's still fair`() {
        val bins = weekly("bins", 10, DayOfWeek.THURSDAY).copy(rotate = true)
        val result = allocate(listOf(bins), listOf(member("harry"), member("sarah")), recent = mapOf("bins" to listOf("harry")))
        assertEquals("sarah", result.assignee("bins"))
        assertTrue(AllocationReason.TakesTurn("harry") in result.reasons("bins"))
    }

    @Test
    fun `fair shares scale how much each person gets`() {
        val chores = (1..6).map { weekly("c$it", 30) }
        val result = allocate(chores, listOf(member("parent"), member("child", share = 0.5)), AllocationSettings(preferenceWeight = 0))
        assertEquals(120, result.load("parent"))
        assertEquals(60, result.load("child"))
    }

    @Test
    fun `someone who did more recently gets less new work`() {
        val result = allocate(listOf(weekly("x", 30)), listOf(member("harry"), member("sarah")), history = mapOf("harry" to 600, "sarah" to 0))
        assertEquals("sarah", result.assignee("x"))
    }

    @Test
    fun `balance decisions explain the difference in minutes`() {
        val locked = weekly("big", 60).copy(assigneeId = "harry", assignmentLocked = true)
        val result = allocate(listOf(locked, weekly("x", 30)), listOf(member("harry"), member("sarah")))
        assertTrue(result.reasons("x").contains(AllocationReason.LessWork(60, "harry")))
    }

    @Test
    fun `results are deterministic and don't depend on input order`() {
        val a = allocate(brief, listOf(harry, sarah))
        val b = allocate(brief.reversed(), listOf(sarah, harry))
        assertEquals(a.proposals.associate { it.choreId to it.assigneeId }, b.proposals.associate { it.choreId to it.assigneeId })
        assertEquals(a, allocate(brief, listOf(harry, sarah)))
    }

    @Test
    fun `only selected chores are reassigned, others keep their assignee and count as load`() {
        val existing = weekly("mine", 60).copy(assigneeId = "harry")
        val result = AllocationEngine.allocate(
            AllocationRequest(listOf(existing, weekly("new", 30)), listOf(member("harry"), member("sarah")), from = monday, choreIds = setOf("new")),
        )
        assertEquals(listOf("new"), result.proposals.map { it.choreId })
        assertEquals("sarah", result.assignee("new"))
    }

    @Test
    fun `category preferences apply when there's no chore-specific one`() {
        val prefs = MemberPreferences("sarah", byCategory = mapOf(ChoreCategory.GARDEN to LOVE))
        val garden = weekly("weed", 30, category = ChoreCategory.GARDEN)
        assertEquals(LOVE, prefs.forChore(garden))
        assertEquals(LIKE, prefs.copy(byChore = mapOf("weed" to LIKE)).forChore(garden))
        assertEquals(NEUTRAL, prefs.forChore(weekly("other", 10)))
    }

    @Test
    fun `difficulty weights effort`() {
        assertEquals(0.8, AllocationEngine.difficultyFactor(1), 1e-9)
        assertEquals(1.0, AllocationEngine.difficultyFactor(3), 1e-9)
        assertEquals(1.2, AllocationEngine.difficultyFactor(5), 1e-9)
    }
}
