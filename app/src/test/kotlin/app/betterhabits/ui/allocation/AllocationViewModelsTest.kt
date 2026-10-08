package app.betterhabits.ui.allocation

import androidx.lifecycle.SavedStateHandle
import app.betterhabits.data.allocation.AllocationPlanner
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.allocation.AllocationReason
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.model.AssignmentSource
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import app.betterhabits.testing.FakeAllocationRepository
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeChoreRepository
import app.betterhabits.testing.FakeHouseholdRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeSyncController
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import app.betterhabits.ui.chores.ChoreEditorViewModel
import app.betterhabits.ui.today.AgendaFilter
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

class AllocationViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-10-08")
    private val clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC)
    private val auth = FakeAuthRepository()
    private val households = FakeHouseholdRepository { "harry" }.apply { names += mapOf("harry" to "Harry", "sarah" to "Sarah", "kid" to "Kid") }
    private val chores = FakeChoreRepository { "harry" }
    private val allocation = FakeAllocationRepository()
    private val planner = AllocationPlanner(chores, allocation, clock)
    private lateinit var householdId: String
    private lateinit var session: HouseholdSession

    @Before
    fun setUp() {
        householdId = households.seedHousehold("Home", "harry", "sarah" to HouseholdRole.MEMBER, "kid" to HouseholdRole.CHILD)
        session = HouseholdSession(auth, households, FakeProfileRepository { households.names }, FakeUserPreferencesRepository(), mainDispatcherRule.appScope)
        auth.signInAs(AuthUser("harry", "harry@example.com", isChild = false))
    }

    private fun chore(id: String, minutes: Int, assignee: String?, category: ChoreCategory = ChoreCategory.OTHER, rotate: Boolean = false) =
        Chore(id, householdId, id, category = category, effort = Effort(minutes), assigneeId = assignee, rotate = rotate,
            schedule = Schedule(Recurrence.Weekly(setOf(DayOfWeek.THURSDAY)), today, zone = ZoneOffset.UTC))
            .also { chores.putChore(it) }

    @Test
    fun `editor suggestion picks the person with less work and explains it`() = runTest {
        chore("big", 90, "harry")
        val vm = ChoreEditorViewModel(SavedStateHandle(), chores, households, session, planner, clock)
        vm.onName("Bins")
        vm.onMinutes(10)
        vm.onToggleExcluded("kid")
        vm.suggestAssignee()

        val state = vm.state.value
        assertEquals("sarah", state.form.assigneeId)
        assertEquals(AssignmentSource.AUTO, state.form.assignmentSource)
        assertTrue(state.suggestion!!.reasons.any { it is AllocationReason.LessWork })

        vm.save()
        assertEquals(AssignmentSource.AUTO, chores.chores.values.first { it.name == "Bins" }.assignmentSource)
    }

    @Test
    fun `choosing someone by hand replaces the suggestion`() = runTest {
        val vm = ChoreEditorViewModel(SavedStateHandle(), chores, households, session, planner, clock)
        vm.onName("Bins")
        vm.suggestAssignee()
        vm.onAssignee("harry")
        assertNull(vm.state.value.suggestion)
        assertEquals(AssignmentSource.MANUAL, vm.state.value.form.assignmentSource)
    }

    @Test
    fun `review proposes changes, honours unticked ones, and applies the rest`() = runTest {
        allocation.preferences["sarah"] = app.betterhabits.domain.allocation.MemberPreferences("sarah", byChore = mapOf("bathroom" to PreferenceLevel.LOVE))
        allocation.preferences["harry"] = app.betterhabits.domain.allocation.MemberPreferences("harry", byChore = mapOf("bathroom" to PreferenceLevel.HATE))
        chore("bathroom", 40, "harry")
        chore("vacuum", 45, null)
        chore("locked", 30, "harry").copy(assignmentLocked = true).also { chores.putChore(it) }

        val vm = AllocationReviewViewModel(planner, households, session)
        val changes = vm.state.value.changes.associate { it.choreId to it.assigneeId }
        assertEquals("sarah", changes["bathroom"])
        assertFalse("locked chores are never proposed", "locked" in changes)
        assertTrue(vm.state.value.accepted.containsAll(changes.keys))

        vm.toggle("vacuum") // keep vacuum as it is
        vm.apply()
        assertTrue(vm.state.value.applied)
        assertEquals("sarah", chores.chores.getValue("bathroom").assigneeId)
        assertEquals(AssignmentSource.AUTO, chores.chores.getValue("bathroom").assignmentSource)
        assertNull(chores.chores.getValue("vacuum").assigneeId)
    }

    @Test
    fun `balance reports each member's estimated time and fair share`() = runTest {
        chore("a", 60, "harry")
        chore("b", 30, "sarah")
        val vm = BalanceViewModel(planner, households, session, clock)
        val w = vm.state.value.workload!!
        assertEquals(60, w.members.first { it.memberId == "harry" }.estimatedMinutes)
        assertEquals(30, w.members.first { it.memberId == "sarah" }.estimatedMinutes)
        assertEquals(1.0 / 3, w.fairShares.getValue("harry"), 1e-9)
    }

    @Test
    fun `preferences save at once and roll back if the server refuses`() = runTest {
        chore("bathroom", 40, null, ChoreCategory.BATHROOM)
        val vm = PreferencesViewModel(SavedStateHandle(), allocation, chores, households, session)
        assertTrue(vm.state.value.canEdit)

        vm.setChorePreference("bathroom", PreferenceLevel.HATE)
        assertEquals(PreferenceLevel.HATE, allocation.preferences.getValue("harry").byChore["bathroom"])

        allocation.nextError = AppError.Network
        vm.setCategoryPreference(ChoreCategory.BATHROOM, PreferenceLevel.LOVE)
        assertNull(vm.state.value.preferences.byCategory[ChoreCategory.BATHROOM])
        assertEquals(AppError.Network, vm.state.value.message)
    }

    @Test
    fun `parents can edit a child's preferences but not another adult's`() = runTest {
        assertTrue(PreferencesViewModel(SavedStateHandle(mapOf("memberId" to "kid")), allocation, chores, households, session).state.value.canEdit)
        assertFalse(PreferencesViewModel(SavedStateHandle(mapOf("memberId" to "sarah")), allocation, chores, households, session).state.value.canEdit)
    }

    @Test
    fun `completing a take-turns chore hands it to the next person`() = runTest {
        chore("bins", 10, "harry", rotate = true).copy(excludedMemberIds = setOf("kid")).also { chores.putChore(it) }
        val vm = TodayViewModel(chores, households, session, FakeSyncController(), planner, clock)
        vm.setFilter(AgendaFilter.EVERYONE)
        vm.toggle(vm.state.value.visible.single { it.chore.id == "bins" })
        assertEquals("sarah", chores.chores.getValue("bins").assigneeId)
    }
}
