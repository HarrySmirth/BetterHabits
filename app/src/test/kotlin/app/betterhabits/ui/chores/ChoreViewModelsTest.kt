package app.betterhabits.ui.chores

import androidx.lifecycle.SavedStateHandle
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeChoreRepository
import app.betterhabits.testing.FakeHouseholdRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import app.betterhabits.ui.today.AgendaFilter
import app.betterhabits.ui.today.SnoozeOption
import app.betterhabits.ui.today.TodayViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChoreViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-10-08") // a Thursday
    private val clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC)
    private val auth = FakeAuthRepository()
    private var me = "harry"
    private val households = FakeHouseholdRepository { me }.apply { names += mapOf("harry" to "Harry", "sarah" to "Sarah", "kid" to "Kid") }
    private val chores = FakeChoreRepository { me }
    private lateinit var householdId: String
    private lateinit var session: HouseholdSession

    @Before
    fun setUp() {
        householdId = households.seedHousehold("Home", "harry", "sarah" to HouseholdRole.MEMBER, "kid" to HouseholdRole.CHILD)
        session = HouseholdSession(auth, households, FakeProfileRepository { households.names }, FakeUserPreferencesRepository(), mainDispatcherRule.appScope)
    }

    private fun signIn(userId: String) {
        me = userId
        auth.signInAs(AuthUser(userId, "$userId@example.com", isChild = userId == "kid"))
    }

    private fun chore(id: String, recurrence: Recurrence, assignee: String?, start: LocalDate = today.minusDays(14), minutes: Int = 10, active: Boolean = true) =
        Chore(id, householdId, id, effort = Effort(minutes), schedule = Schedule(recurrence, start, zone = ZoneOffset.UTC), assigneeId = assignee, active = active)
            .also { chores.chores[id] = it }

    private fun today() = TodayViewModel(chores, households, session, clock)

    @Test
    fun `today shows my overdue and due items with progress and effort left`() = runTest {
        chore("dishes", Recurrence.Daily(), "harry", minutes = 15)
        chore("bins", Recurrence.Weekly(setOf(DayOfWeek.MONDAY)), "harry", minutes = 10) // overdue since Monday
        chore("bathroom", Recurrence.Daily(), "sarah", minutes = 40)
        signIn("harry")

        val state = today().state.value
        assertEquals(listOf("bins"), state.overdue.map { it.chore.id })
        assertEquals(listOf("dishes"), state.dueToday.map { it.chore.id })
        assertEquals(Effort(10 + 15), state.remainingEffort) // yesterday's dishes are missed, not double-counted
        assertFalse(state.visible.any { it.chore.id == "bathroom" })
    }

    @Test
    fun `everyone filter includes other people's chores`() = runTest {
        chore("bathroom", Recurrence.Daily(), "sarah")
        signIn("harry")
        val vm = today()
        vm.setFilter(AgendaFilter.EVERYONE)
        assertTrue(vm.state.value.visible.any { it.chore.id == "bathroom" })
    }

    @Test
    fun `completing records who did it, offers undo, and undo restores it`() = runTest {
        chore("bathroom", Recurrence.Once, "sarah", start = today)
        signIn("harry")
        val vm = today()
        vm.setFilter(AgendaFilter.EVERYONE)
        val occurrence = vm.state.value.visible.single()

        vm.toggle(occurrence)
        val record = chores.records.getValue("bathroom" to OccurrenceKey(today))
        assertEquals(OccurrenceStatus.COMPLETED, record.status)
        assertEquals("harry", record.completedBy)
        assertEquals("sarah", vm.state.value.done.single().assigneeId)
        val undo = vm.state.value.undo!!

        vm.undo(undo)
        assertEquals(OccurrenceStatus.PENDING, chores.records.getValue("bathroom" to OccurrenceKey(today)).status)
        assertEquals(OccurrenceState.UPCOMING, vm.state.value.visible.single().state)
    }

    @Test
    fun `failed completion is rolled back with a message`() = runTest {
        chore("dishes", Recurrence.Once, "harry", start = today)
        signIn("harry")
        val vm = today()
        chores.nextError = AppError.Network
        vm.toggle(vm.state.value.visible.single())

        assertEquals(AppError.Network, vm.state.value.message)
        assertEquals(OccurrenceState.UPCOMING, vm.state.value.visible.single().state)
        assertNull(vm.state.value.undo)
    }

    @Test
    fun `snooze hides an item until later and skip marks it skipped`() = runTest {
        chore("vacuum", Recurrence.Once, "harry", start = today)
        chore("dust", Recurrence.Once, "harry", start = today)
        signIn("harry")
        val vm = today()

        vm.snooze(vm.state.value.visible.first { it.chore.id == "vacuum" }, SnoozeOption.ONE_HOUR)
        assertFalse(vm.state.value.visible.any { it.chore.id == "vacuum" })

        vm.skip(vm.state.value.visible.first { it.chore.id == "dust" })
        assertEquals(OccurrenceState.SKIPPED, vm.state.value.visible.first { it.chore.id == "dust" }.state)
    }

    @Test
    fun `ticking a skipped chore completes it, and the server matches the last tap`() = runTest {
        chore("bins", Recurrence.Once, "harry", start = today)
        signIn("harry")
        val vm = today()
        val key = "bins" to OccurrenceKey(today)

        vm.skip(vm.state.value.visible.single())
        vm.toggle(vm.state.value.visible.single()) // tick a skipped item -> completed (not reset)
        assertEquals(OccurrenceState.COMPLETED, vm.state.value.visible.single().state)
        assertEquals(OccurrenceStatus.COMPLETED, chores.records.getValue(key).status)

        vm.toggle(vm.state.value.visible.single()) // untick -> pending
        assertEquals(OccurrenceState.UPCOMING, vm.state.value.visible.single().state)
        assertEquals(OccurrenceStatus.PENDING, chores.records.getValue(key).status)
        assertNull("unticking clears the completion's undo", vm.state.value.undo)
    }

    @Test
    fun `undo on a skipped chore returns it to pending`() = runTest {
        chore("bins", Recurrence.Once, "harry", start = today)
        signIn("harry")
        val vm = today()
        vm.skip(vm.state.value.visible.single())
        vm.reset(vm.state.value.visible.single())
        assertEquals(OccurrenceState.UPCOMING, vm.state.value.visible.single().state)
    }

    @Test
    fun `children can only act on their own chores`() = runTest {
        chore("tidy", Recurrence.Once, "kid", start = today)
        chore("bins", Recurrence.Once, "harry", start = today)
        signIn("kid")
        val vm = today()
        vm.setFilter(AgendaFilter.EVERYONE)
        val state = vm.state.value
        assertTrue(state.canAct(state.visible.first { it.chore.id == "tidy" }))
        assertFalse(state.canAct(state.visible.first { it.chore.id == "bins" }))
    }

    @Test
    fun `chores list orders by next due and separates paused chores`() = runTest {
        chore("monthly", Recurrence.MonthlyOnDay(20), "harry")
        chore("daily", Recurrence.Daily(), "harry")
        chore("paused", Recurrence.Daily(), null, active = false)
        signIn("harry")
        val state = ChoresViewModel(chores, households, session, clock).state.value
        assertEquals(listOf("daily", "monthly"), state.active.map { it.chore.id })
        assertEquals(today, state.active.first().next?.key?.date)
        assertEquals(listOf("paused"), state.paused.map { it.chore.id })
    }

    @Test
    fun `editor validates, then creates a weekly chore`() = runTest {
        signIn("harry")
        val vm = ChoreEditorViewModel(SavedStateHandle(), chores, households, session, clock)
        val form = vm.state.value.form
        assertEquals(today, form.startDate)
        assertEquals(setOf(DayOfWeek.THURSDAY), form.weekdays)

        vm.save()
        assertTrue("name is required", vm.state.value.showValidation)
        assertTrue(chores.chores.isEmpty())

        vm.onName("Take bins out")
        vm.onToggleWeekday(DayOfWeek.MONDAY)
        vm.onMinutes(10)
        vm.onAssignee("sarah")
        vm.save()

        assertTrue(vm.state.value.saved)
        val saved = chores.chores.values.single()
        assertEquals("Take bins out", saved.name)
        assertEquals(Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), saved.schedule.recurrence)
        assertEquals("sarah", saved.assigneeId)
        assertEquals(Effort(10), saved.effort)
    }

    @Test
    fun `editor monthly options are anchored to the start date`() = runTest {
        signIn("harry")
        val vm = ChoreEditorViewModel(SavedStateHandle(), chores, households, session, clock)
        vm.onRepeat(RepeatKind.MONTHLY)
        assertEquals(Recurrence.MonthlyOnDay(8), vm.state.value.form.recurrence())
        vm.onMonthlyMode(MonthlyMode.WEEKDAY)
        assertEquals(Recurrence.MonthlyOnWeekday(2, DayOfWeek.THURSDAY), vm.state.value.form.recurrence())
        vm.onStartDate(LocalDate.parse("2026-10-29")) // the 5th Thursday counts as "last"
        assertEquals(Recurrence.MonthlyOnWeekday(-1, DayOfWeek.THURSDAY), vm.state.value.form.recurrence())
    }

    @Test
    fun `editor loads an existing chore and saves edits in place`() = runTest {
        chore("dishes", Recurrence.Daily(interval = 2), "harry", minutes = 20)
        signIn("harry")
        val vm = ChoreEditorViewModel(SavedStateHandle(mapOf("choreId" to "dishes")), chores, households, session, clock)
        assertEquals(RepeatKind.DAILY, vm.state.value.form.repeat)
        assertEquals(2, vm.state.value.form.interval)

        vm.onName("Wash up")
        vm.save()
        assertEquals("Wash up", chores.chores.getValue("dishes").name)
        assertEquals(1, chores.chores.size)
    }

    @Test
    fun `history includes completed, skipped and missed occurrences`() = runTest {
        chore("dishes", Recurrence.Daily(), "harry", start = today.minusDays(3))
        chores.records["dishes" to OccurrenceKey(today.minusDays(3))] =
            app.betterhabits.domain.model.OccurrenceRecord("dishes", OccurrenceKey(today.minusDays(3)), OccurrenceStatus.COMPLETED, completedBy = "sarah", completedAt = clock.instant())
        chores.records["dishes" to OccurrenceKey(today.minusDays(2))] =
            app.betterhabits.domain.model.OccurrenceRecord("dishes", OccurrenceKey(today.minusDays(2)), OccurrenceStatus.SKIPPED)
        signIn("harry")

        val vm = HistoryViewModel(chores, households, session, clock)
        val states = vm.state.value.entries.map { it.key.date to it.state }.toSet()
        assertTrue(today.minusDays(3) to OccurrenceState.COMPLETED in states)
        assertTrue(today.minusDays(2) to OccurrenceState.SKIPPED in states)
        assertTrue("yesterday's undone daily chore is missed", today.minusDays(1) to OccurrenceState.MISSED in states)
        assertFalse("today's is still upcoming", states.any { it.first == today })

        vm.setPerson("sarah")
        assertEquals(listOf(today.minusDays(3)), vm.state.value.visible.map { it.key.date })
    }
}
