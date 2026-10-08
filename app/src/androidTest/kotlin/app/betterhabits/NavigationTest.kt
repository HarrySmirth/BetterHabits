package app.betterhabits

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun startsOnToday() {
        tab("Today").assertIsSelected()
        compose.onNode(hasText("Today") and isHeading()).assertExists()
    }

    @Test
    fun bottomBarNavigatesBetweenTopLevelScreens() {
        listOf("Chores", "Habits", "Household", "Profile", "Today").forEach { label ->
            tab(label).performClick()
            tab(label).assertIsSelected()
            compose.onNode(hasText(label) and isHeading()).assertExists()
        }
    }
}
