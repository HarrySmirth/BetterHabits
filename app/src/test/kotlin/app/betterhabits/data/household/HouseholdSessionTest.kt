package app.betterhabits.data.household

import app.betterhabits.data.auth.AuthUser
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeHouseholdRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HouseholdSessionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val harry = AuthUser("harry", "harry@example.com", isChild = false)
    private val auth = FakeAuthRepository()
    private val households = FakeHouseholdRepository { "harry" }
    private val prefs = FakeUserPreferencesRepository()
    private val profiles = FakeProfileRepository { emptyMap() }

    @Test
    fun `follows auth state and selects the first household by default`() = runTest {
        val alpha = households.seedHousehold("Alpha", "harry")
        households.seedHousehold("Beta", "harry")
        val session = HouseholdSession(auth, households, profiles, prefs, mainDispatcherRule.appScope)

        assertEquals(SessionState.SignedOut, session.state.value)

        auth.signInAs(harry)
        val ready = session.state.value as SessionState.Ready
        assertEquals(listOf("Alpha", "Beta"), ready.households.map { it.household.name })
        assertEquals(alpha, ready.selected?.household?.id)
    }

    @Test
    fun `selection is persisted and unknown selections fall back`() = runTest {
        households.seedHousehold("Alpha", "harry")
        val beta = households.seedHousehold("Beta", "harry")
        val session = HouseholdSession(auth, households, profiles, prefs, mainDispatcherRule.appScope)
        auth.signInAs(harry)

        session.select(beta)
        assertEquals(beta, (session.state.value as SessionState.Ready).selected?.household?.id)
        assertEquals(beta, prefs.preferences.value.selectedHouseholdId)

        prefs.setSelectedHouseholdId("deleted-household")
        assertEquals("Alpha", (session.state.value as SessionState.Ready).selected?.household?.name)
    }

    @Test
    fun `refresh picks up newly created households and sign-out clears state`() = runTest {
        val session = HouseholdSession(auth, households, profiles, prefs, mainDispatcherRule.appScope)
        auth.signInAs(harry)
        assertTrue((session.state.value as SessionState.Ready).households.isEmpty())

        households.createHousehold("Home", "UTC")
        session.refresh()
        assertEquals(1, (session.state.value as SessionState.Ready).households.size)

        auth.signOut()
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `device timezone is synced to the profile on sign-in`() = runTest {
        HouseholdSession(auth, households, profiles, prefs, mainDispatcherRule.appScope)
        auth.signInAs(harry)
        assertEquals(java.time.ZoneId.systemDefault().id, profiles.timezones["harry"])
    }
}
