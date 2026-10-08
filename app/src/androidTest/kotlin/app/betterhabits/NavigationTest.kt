package app.betterhabits

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.betterhabits.data.auth.AuthUser
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun signedInWithHousehold() {
        val fakes = resetFakeContainer()
        fakes.householdRepository.names["harry"] = "Harry"
        fakes.householdRepository.seedHousehold("Harry & Sarah", ownerId = "harry")
        fakes.authRepository.signInAs(AuthUser("harry", "harry@example.com", isChild = false))
        ActivityScenario.launch(MainActivity::class.java)
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun startsOnToday() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Today") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        tab("Today").assertIsSelected()
        compose.onNode(hasText("Today") and isHeading()).assertExists()
    }

    @Test
    fun bottomBarNavigatesBetweenTopLevelScreens() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Chores") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        listOf("Chores", "Habits", "Profile", "Today").forEach { label ->
            tab(label).performClick()
            tab(label).assertIsSelected()
            compose.onNode(hasText(label) and isHeading()).assertExists()
        }
        tab("Household").performClick()
        tab("Household").assertIsSelected()
        compose.onNodeWithTextEventually("Harry & Sarah")
    }
}
