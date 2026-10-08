package app.betterhabits.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.betterhabits.BuildConfig
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.SupabaseAuthRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SupabaseHouseholdRepository
import app.betterhabits.data.preferences.DataStoreUserPreferencesRepository
import app.betterhabits.data.preferences.UserPreferencesRepository
import app.betterhabits.data.profile.ProfileRepository
import app.betterhabits.data.profile.SupabaseProfileRepository
import app.betterhabits.data.remote.SupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. The object graph is small, so a DI framework (Hilt) would add
 * build time and annotation processing without paying for itself. Revisit if the graph grows
 * to the point where wiring by hand becomes error-prone.
 */
interface AppContainer {
    val userPreferencesRepository: UserPreferencesRepository
    val authRepository: AuthRepository
    val householdRepository: HouseholdRepository
    val profileRepository: ProfileRepository
    val householdSession: HouseholdSession
}

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    /** Lives as long as the process; used for app-wide state such as [HouseholdSession]. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val supabase by lazy { SupabaseProvider.create() }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(appContext.userPreferencesDataStore)
    }

    override val authRepository: AuthRepository by lazy {
        SupabaseAuthRepository(supabase, BuildConfig.CHILD_EMAIL_DOMAIN)
    }

    override val householdRepository: HouseholdRepository by lazy { SupabaseHouseholdRepository(supabase) }

    override val profileRepository: ProfileRepository by lazy { SupabaseProfileRepository(supabase) }

    override val householdSession: HouseholdSession by lazy {
        HouseholdSession(authRepository, householdRepository, profileRepository, userPreferencesRepository, applicationScope)
    }
}
