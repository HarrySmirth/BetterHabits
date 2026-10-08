package app.betterhabits.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Remembers who is signed in on this device, so the app can open offline. Supabase only reports
 * a user once their session is confirmed or refreshed, which needs the network.
 */
interface LastUserStore {
    suspend fun get(): AuthUser?
    suspend fun set(user: AuthUser?)
}

class DataStoreLastUserStore(private val dataStore: DataStore<Preferences>) : LastUserStore {

    override suspend fun get(): AuthUser? {
        val prefs = dataStore.data.first()
        val id = prefs[ID] ?: return null
        return AuthUser(id = id, email = prefs[EMAIL], isChild = prefs[IS_CHILD] ?: false)
    }

    override suspend fun set(user: AuthUser?) {
        dataStore.edit { prefs ->
            if (user == null) {
                prefs.remove(ID)
                prefs.remove(EMAIL)
                prefs.remove(IS_CHILD)
            } else {
                prefs[ID] = user.id
                if (user.email == null) prefs.remove(EMAIL) else prefs[EMAIL] = user.email
                prefs[IS_CHILD] = user.isChild
            }
        }
    }

    private companion object {
        val ID = stringPreferencesKey("last_user_id")
        val EMAIL = stringPreferencesKey("last_user_email")
        val IS_CHILD = booleanPreferencesKey("last_user_is_child")
    }
}
