package app.betterhabits.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = dataStore.data
        // A corrupt or unreadable file should fall back to defaults rather than crash the app.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserPreferences(
                themeMode = prefs[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.SYSTEM,
                useDynamicColor = prefs[DYNAMIC_COLOR] ?: false,
                selectedHouseholdId = prefs[SELECTED_HOUSEHOLD],
            )
        }

    override suspend fun setSelectedHouseholdId(id: String?) {
        dataStore.edit { if (id == null) it.remove(SELECTED_HOUSEHOLD) else it[SELECTED_HOUSEHOLD] = id }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SELECTED_HOUSEHOLD = stringPreferencesKey("selected_household_id")
    }
}
