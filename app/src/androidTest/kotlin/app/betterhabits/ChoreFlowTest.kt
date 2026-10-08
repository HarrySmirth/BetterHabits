package app.betterhabits

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import app.betterhabits.testing.FakeAppContainer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Create, assign and complete chores through the real UI (fake backend). */
@RunWith(AndroidJUnit4::class)
class ChoreFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var fakes: FakeAppContainer
    private lateinit var householdId: String

    @Before
    fun setUp() {
        fakes = resetFakeContainer()
        fakes.householdRepository.names += mapOf("harry" to "Harry", "sarah" to "Sarah")
        householdId = fakes.householdRepository.seedHousehold("Home", "harry", "sarah" to HouseholdRole.MEMBER)
        fakes.authRepository.signInAs(AuthUser("harry", "harry@example.com", isChild = false))
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun createAChoreAssignedToSomeoneElse() {
        ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Add chore")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Add chore")).performClick()
        compose.typeInto("choreName", "Take bins out")
        compose.onNode(hasText("Sarah") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) { fakes.choreRepository.chores.isNotEmpty() }
        val chore = fakes.choreRepository.chores.values.single()
        assertEquals("Take bins out", chore.name)
        assertEquals("sarah", chore.assigneeId)

        tab("Chores").performClick()
        compose.onNodeWithTextEventually("Take bins out")
        compose.onNodeWithTextEventually("Sarah", substring = true)
    }

    @Test
    fun completeTodaysChoreAndSeeProgress() {
        val today = LocalDate.now(ZoneId.of("UTC"))
        fakes.choreRepository.putChore(Chore(
            id = "dishes",
            householdId = householdId,
            name = "Empty dishwasher",
            effort = Effort(5),
            schedule = Schedule(Recurrence.Daily(), today, zone = ZoneId.of("UTC")),
            assigneeId = "harry",
        ))
        ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithTextEventually("0 of 1 chore done")
        compose.onNode(isToggleable()).performClick()

        compose.onNodeWithTextEventually("1 of 1 chore done")
        compose.onNodeWithTextEventually("Done")
        compose.waitUntil(5_000) { fakes.choreRepository.records.values.any { it.status == OccurrenceStatus.COMPLETED } }
        assertEquals("harry", fakes.choreRepository.records.values.single().completedBy)
    }
}
