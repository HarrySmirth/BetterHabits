package app.betterhabits.ui.profile

import app.betterhabits.data.auth.AuthState
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.SessionState
import app.betterhabits.data.preferences.ThemeMode
import app.betterhabits.data.preferences.UserPreferences
import app.betterhabits.domain.error.AppError
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = AuthUser("harry", "harry@example.com", isChild = false)
    private val auth = FakeAuthRepository(AuthState.SignedIn(user))

    private fun viewModel(prefs: FakeUserPreferencesRepository = FakeUserPreferencesRepository()) = ProfileViewModel(
        prefs,
        auth,
        FakeProfileRepository { mapOf("harry" to "Harry") },
        MutableStateFlow(SessionState.Ready(user, emptyList(), null)),
    )

    @Test
    fun `reflects stored preferences and persists changes`() = runTest {
        val repository = FakeUserPreferencesRepository(UserPreferences(ThemeMode.SYSTEM, useDynamicColor = true))
        val viewModel = viewModel(repository)

        viewModel.preferences.test {
            assertEquals(UserPreferences(ThemeMode.SYSTEM, true), awaitItem())

            viewModel.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem().themeMode)

            viewModel.setUseDynamicColor(false)
            assertEquals(UserPreferences(ThemeMode.DARK, false), awaitItem())
        }
    }

    @Test
    fun `loads the signed-in account and display name`() = runTest {
        val account = viewModel().account.value
        assertEquals(user, account.user)
        assertEquals("Harry", account.displayName)
    }

    @Test
    fun `sign out clears the session`() = runTest {
        viewModel().signOut()
        assertEquals(AuthState.SignedOut, auth.authState.value)
    }

    @Test
    fun `account deletion failure is surfaced, not swallowed`() = runTest {
        auth.nextError = AppError.TransferOwnershipRequired("Harry & Sarah")
        val viewModel = viewModel()
        viewModel.deleteAccount()

        val account = viewModel.account.value
        assertFalse(account.deleting)
        assertEquals(AppError.TransferOwnershipRequired("Harry & Sarah"), account.message)
        assertTrue(auth.authState.value is AuthState.SignedIn)
    }
}
