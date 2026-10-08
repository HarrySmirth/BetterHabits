package app.betterhabits.testing

import app.betterhabits.data.preferences.ThemeMode
import app.betterhabits.data.preferences.UserPreferences
import app.betterhabits.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val preferences: StateFlow<UserPreferences> = state

    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }

    override suspend fun setUseDynamicColor(enabled: Boolean) = state.update { it.copy(useDynamicColor = enabled) }

    override suspend fun setSelectedHouseholdId(id: String?) = state.update { it.copy(selectedHouseholdId = id) }
}
