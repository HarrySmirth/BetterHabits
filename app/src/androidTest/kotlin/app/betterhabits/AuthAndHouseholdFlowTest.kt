package app.betterhabits

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.betterhabits.testing.FakeAppContainer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Critical signed-out → household flows, run against in-memory fakes. */
@RunWith(AndroidJUnit4::class)
class AuthAndHouseholdFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var fakes: FakeAppContainer

    @Before
    fun setUp() {
        fakes = resetFakeContainer()
        fakes.authRepository.addAccount("harry@example.com", "correct-horse", name = "Harry", id = "harry")
        fakes.householdRepository.names["harry"] = "Harry"
        ActivityScenario.launch(MainActivity::class.java)
    }

    private fun signIn(email: String, password: String) {
        compose.typeInto("email", email)
        compose.typeInto("password", password)
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
    }

    private fun openHouseholdTab() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Household") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Household") and isSelectable()).performClick()
    }

    @Test
    fun wrongPasswordShowsFriendlyErrorAndCorrectOneSignsIn() {
        signIn("harry@example.com", "wrong")
        compose.onNodeWithTextEventually("That email and password don't match.")

        compose.onNodeWithTag("password").performTextClearance()
        compose.onNodeWithTag("password").performTextInput("correct-horse")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        compose.onNodeWithTextEventually("Set up your household")
    }

    @Test
    fun signInThenCreateHouseholdShowsItWithMeAsOwner() {
        signIn("harry@example.com", "correct-horse")
        compose.onNodeWithTextEventually("Set up your household")

        compose.typeInto("householdName", "Harry & Sarah")
        compose.onNodeWithText("Create household").performScrollTo().performClick()

        openHouseholdTab()
        compose.onNodeWithTextEventually("Harry & Sarah")
        compose.onNodeWithTextEventually("Harry (you)")
        compose.onNodeWithTextEventually("Owner")
    }

    @Test
    fun joinHouseholdWithInviteCode() {
        fakes.householdRepository.names["sarah"] = "Sarah"
        val household = fakes.householdRepository.seedHousehold("Sarah's Place", ownerId = "sarah")
        fakes.householdRepository.seedInviteCode(household, "H7K4P9QX")

        signIn("harry@example.com", "correct-horse")
        compose.onNodeWithTextEventually("Join with an invite code")
        compose.typeInto("inviteCode", "h7k4-p9qx")
        compose.onNode(hasText("Join") and hasClickAction()).performScrollTo().performClick()

        openHouseholdTab()
        compose.onNodeWithTextEventually("Sarah's Place")
        compose.onNodeWithTextEventually("Members (2)")
    }

    @Test
    fun invalidInviteCodeIsRejected() {
        signIn("harry@example.com", "correct-horse")
        compose.typeInto("inviteCode", "ZZZZZZZZ")
        compose.onNode(hasText("Join") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTextEventually("That invite code isn't valid. Check it and try again.")
    }

    @Test
    fun signUpRequiresEmailCodeThenReachesSetup() {
        compose.onNodeWithTextEventually("Create an account").performClick()
        compose.onNodeWithTextEventually("Create your account")
        compose.typeInto("displayName", "Sarah")
        compose.typeInto("email", "sarah@example.com")
        compose.typeInto("password", "long enough pw")
        compose.onNode(hasText("Create an account") and hasClickAction()).performClick()

        compose.onNodeWithTextEventually("Check your email")
        compose.typeInto("otp", fakes.authRepository.signUpCode)
        compose.onNodeWithTextEventually("Set up your household")
    }
}
