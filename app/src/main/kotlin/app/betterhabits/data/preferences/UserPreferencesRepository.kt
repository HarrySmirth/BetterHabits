package app.betterhabits.data.preferences

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Device-local settings. Not synced: each device can look different. */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    /** Household shown in household-scoped screens; falls back to the first one when null/unknown. */
    val selectedHouseholdId: String? = null,
)

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setUseDynamicColor(enabled: Boolean)

    suspend fun setSelectedHouseholdId(id: String?)
}
