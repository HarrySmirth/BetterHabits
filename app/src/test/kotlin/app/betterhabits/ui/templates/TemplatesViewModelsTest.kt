package app.betterhabits.ui.templates

import androidx.lifecycle.SavedStateHandle
import app.betterhabits.data.allocation.AllocationPlanner
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.template.BuiltInTemplates
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreTemplate
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import app.betterhabits.testing.FakeAllocationRepository
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeChoreRepository
import app.betterhabits.testing.FakeHouseholdRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeSyncController
import app.betterhabits.testing.FakeTemplateRepository
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
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TemplatesViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-10-08")
    private val clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC)
    private val auth = FakeAuthRepository()
    private val households = FakeHouseholdRepository { "harry" }.apply { names += mapOf("harry" to "Harry", "sarah" to "Sarah", "kid" to "Kid") }
    private val chores = FakeChoreRepository { "harry" }
    private val allocation = FakeAllocationRepository()
    private val templates = FakeTemplateRepository()
    private lateinit var householdId: String
    private lateinit var session: HouseholdSession

    @Before
    fun setUp() {
        householdId = households.seedHousehold("Home", "harry", "sarah" to HouseholdRole.MEMBER, "kid" to HouseholdRole.CHILD)
        session = HouseholdSession(auth, households, FakeProfileRepository { households.names }, FakeUserPreferencesRepository(), mainDispatcherRule.appScope)
        auth.signInAs(AuthUser("harry", "harry@example.com", isChild = false))
    }

    private fun library() = TemplatesViewModel(templates, allocation, households, session)
    private fun editor(vararg args: Pair<String, String>) =
        TemplateEditorViewModel(SavedStateHandle(mapOf(*args)), templates, chores, households, session)

    @Test
    fun `the bundled starter library is valid and covers every category`() {
        val list = BuiltInTemplates.parse(File("src/main/assets/templates/builtin.json").readText())
        assertTrue(list.size >= 30)
        assertEquals(list.size, list.map { it.id }.toSet().size)
        assertTrue(list.all { it.id.startsWith(ChoreTemplate.BUILT_IN_PREFIX) && it.id.length <= 64 })
        assertEquals(5, list.single { it.id == "builtin.bathroom.deep_clean" }.checklist.size)
        assertEquals(6, list.map { it.category }.toSet().size)
    }

    @Test
    fun `library groups templates, searches steps too, and remembers my template preferences`() = runTest {
        templates.save(ChoreTemplate("", TemplateScope.HOUSEHOLD, "Sunday reset", effort = Effort(30), householdId = householdId))
        allocation.preferences["harry"] = app.betterhabits.domain.allocation.MemberPreferences("harry", byTemplate = mapOf("builtin.household.take_bins_out" to PreferenceLevel.LIKE))
        val vm = library()
        assertEquals(listOf("Sunday reset"), vm.state.value.household.map { it.name })
        assertEquals(PreferenceLevel.LIKE, vm.state.value.preferences["builtin.household.take_bins_out"])

        vm.onQuery("mirror") // a step of the bathroom deep clean
        assertEquals(listOf("Bathroom deep clean"), vm.state.value.builtIn.flatMap { it.second }.map { it.name })
        vm.onQuery("zzz")
        assertTrue(vm.state.value.nothingMatches)
    }

    @Test
    fun `setting a template preference saves it and rolls back if refused`() = runTest {
        val vm = library()
        val bins = vm.state.value.templates.first { it.id == "builtin.household.take_bins_out" }
        vm.setPreference(bins, PreferenceLevel.HATE)
        assertEquals(PreferenceLevel.HATE, allocation.preferences.getValue("harry").byTemplate[bins.id])

        allocation.nextError = AppError.Network
        vm.setPreference(bins, PreferenceLevel.LOVE)
        assertEquals(PreferenceLevel.HATE, vm.state.value.preferences[bins.id])
        assertEquals(AppError.Network, vm.state.value.message)
    }

    @Test
    fun `only template managers edit household templates, and anyone edits their own`() = runTest {
        val vm = library()
        val shared = ChoreTemplate("t1", TemplateScope.HOUSEHOLD, "Shared", effort = Effort(5), householdId = householdId)
        val mine = ChoreTemplate("t2", TemplateScope.PERSONAL, "Mine", effort = Effort(5), ownerId = "harry")
        val theirs = mine.copy(id = "t3", ownerId = "sarah")
        assertTrue(vm.state.value.canEdit(shared))
        assertTrue(vm.state.value.canEdit(mine))
        assertFalse(vm.state.value.canEdit(theirs))
        assertFalse(vm.state.value.canEdit(templates.builtIn.first()))
    }

    @Test
    fun `copying a built-in template creates an editable household template`() = runTest {
        val vm = editor("copyOf" to "builtin.bathroom.deep_clean")
        assertEquals(TemplateScope.HOUSEHOLD, vm.state.value.form.scope) // owner can manage templates
        vm.onName("Our bathroom")
        vm.onMoveStep(4, -1)
        vm.onAddStep("Empty the bin")
        vm.save()
        assertTrue(vm.state.value.saved)
        val saved = templates.saved.values.single()
        assertEquals("Our bathroom", saved.name)
        assertEquals(householdId, saved.householdId)
        assertEquals(listOf("Clean toilet", "Clean sink", "Clean shower", "Mop floor", "Clean mirror", "Empty the bin"), saved.checklist)
    }

    @Test
    fun `saving a chore as a template keeps its definition`() = runTest {
        chores.putChore(
            Chore("c1", householdId, "Hoover stairs", effort = Effort(20), checklist = listOf("Top", "Bottom"),
                schedule = Schedule(Recurrence.Weekly(setOf(DayOfWeek.MONDAY), 2), today, zone = ZoneOffset.UTC), assigneeId = "sarah"),
        )
        val vm = editor("fromChoreId" to "c1")
        vm.onScope(TemplateScope.PERSONAL)
        vm.save()
        val saved = templates.saved.values.single()
        assertEquals(TemplateScope.PERSONAL, saved.scope)
        assertEquals("harry", saved.ownerId)
        assertEquals(RepeatKind.WEEKLY, saved.repeatKind)
        assertEquals(2, saved.repeatInterval)
        assertEquals(listOf("Top", "Bottom"), saved.checklist)
    }

    @Test
    fun `members without MANAGE_TEMPLATES can only make personal templates`() = runTest {
        households.setRolePermission(householdId, HouseholdRole.MEMBER, HouseholdPermission.MANAGE_TEMPLATES, granted = false)
        auth.signInAs(AuthUser("sarah", "sarah@example.com", isChild = false))
        val vm = editor()
        assertEquals(TemplateScope.PERSONAL, vm.state.value.form.scope)
        vm.onScope(TemplateScope.HOUSEHOLD)
        assertEquals(TemplateScope.PERSONAL, vm.state.value.form.scope)
    }

    @Test
    fun `using a template prefills the chore editor and the chore remembers it`() = runTest {
        val planner = AllocationPlanner(chores, allocation, clock)
        val vm = ChoreEditorViewModel(SavedStateHandle(mapOf("templateId" to "builtin.bathroom.deep_clean")), chores, households, session, planner, templates, clock)
        val state = vm.state.value
        assertEquals("Bathroom deep clean", state.form.name)
        assertEquals("Bathroom deep clean", state.templateName)
        assertEquals(setOf(DayOfWeek.THURSDAY), state.form.weekdays)
        assertTrue(state.showAdvanced)
        vm.save()
        val chore = chores.chores.values.single()
        assertEquals("builtin.bathroom.deep_clean", chore.templateId)
        assertEquals(5, chore.checklist.size)
    }

    @Test
    fun `ticking steps records progress and the last step completes the chore`() = runTest {
        chores.putChore(
            Chore("bath", householdId, "Bathroom", effort = Effort(30), checklist = listOf("Toilet", "Sink"), assigneeId = "harry",
                schedule = Schedule(Recurrence.Daily(), today, zone = ZoneOffset.UTC)),
        )
        val vm = TodayViewModel(chores, households, session, FakeSyncController(), AllocationPlanner(chores, allocation, clock), clock)
        vm.setFilter(AgendaFilter.MINE)
        fun bath() = vm.state.value.visible.single { it.chore.id == "bath" }

        vm.toggleStep(bath(), 1)
        assertEquals(setOf(1), bath().checkedSteps)
        assertNull(chores.records.values.single().completedAt)

        vm.toggleStep(bath(), 0)
        val record = chores.records.values.single()
        assertEquals(OccurrenceStatus.COMPLETED, record.status)
        assertEquals(setOf(0, 1), record.checkedSteps)
        assertTrue(vm.state.value.undo!!.completed)
    }
}
