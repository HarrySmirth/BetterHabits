package app.betterhabits.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.betterhabits.data.preferences.DataStoreUserPreferencesRepository
import app.betterhabits.data.preferences.UserPreferencesRepository

/**
 * Manual dependency container. The object graph is small, so a DI framework (Hilt) would add
 * build time and annotation processing without paying for itself. Revisit if the graph grows
 * to the point where wiring by hand becomes error-prone.
 */
interface AppContainer {
    val userPreferencesRepository: UserPreferencesRepository
}

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(appContext.userPreferencesDataStore)
    }
}
