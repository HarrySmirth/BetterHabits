package app.betterhabits.ui.profile

import app.betterhabits.data.preferences.ThemeMode
import app.betterhabits.data.preferences.UserPreferences
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `reflects stored preferences and persists changes`() = runTest {
        val repository = FakeUserPreferencesRepository(UserPreferences(ThemeMode.SYSTEM, useDynamicColor = true))
        val viewModel = ProfileViewModel(repository)

        viewModel.preferences.test {
            assertEquals(UserPreferences(ThemeMode.SYSTEM, true), awaitItem())

            viewModel.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem().themeMode)

            viewModel.setUseDynamicColor(false)
            assertEquals(UserPreferences(ThemeMode.DARK, false), awaitItem())
        }
    }
}
