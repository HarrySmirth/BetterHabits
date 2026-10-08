package app.betterhabits.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.betterhabits.BuildConfig
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.AuthState
import app.betterhabits.data.auth.DataStoreLastUserStore
import app.betterhabits.data.auth.SupabaseAuthRepository
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.chore.OfflineChoreRepository
import app.betterhabits.data.household.CachingHouseholdRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.data.household.SupabaseHouseholdRepository
import app.betterhabits.data.local.AppDatabase
import app.betterhabits.data.preferences.DataStoreUserPreferencesRepository
import app.betterhabits.data.preferences.UserPreferencesRepository
import app.betterhabits.data.profile.ProfileRepository
import app.betterhabits.data.profile.SupabaseProfileRepository
import app.betterhabits.data.remote.SupabaseProvider
import app.betterhabits.data.sync.RealtimeSync
import app.betterhabits.data.sync.RoomSyncStore
import app.betterhabits.data.sync.SupabaseChoreRemote
import app.betterhabits.data.sync.SyncController
import app.betterhabits.data.sync.SyncEngine
import app.betterhabits.data.sync.SyncWorker
import app.betterhabits.data.sync.onlineFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val choreRepository: ChoreRepository
    val syncController: SyncController

    /** Background worker entry point; null where there is no real sync (tests). */
    val syncEngine: SyncEngine?

    /** Called by the activity: realtime updates only run while the app is visible. */
    fun setForeground(foreground: Boolean)
}

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    /** Lives as long as the process; used for app-wide state such as [HouseholdSession]. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val supabase by lazy { SupabaseProvider.create() }

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "betterhabits.db").build()
    }

    private val online by lazy { appContext.onlineFlow().stateIn(applicationScope, SharingStarted.Eagerly, true) }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(appContext.userPreferencesDataStore)
    }

    override val authRepository: AuthRepository by lazy {
        SupabaseAuthRepository(supabase, BuildConfig.CHILD_EMAIL_DOMAIN, DataStoreLastUserStore(appContext.userPreferencesDataStore))
    }

    override val householdRepository: HouseholdRepository by lazy {
        CachingHouseholdRepository(SupabaseHouseholdRepository(supabase), database.cacheDao(), isOnline = { online.value })
    }

    override val profileRepository: ProfileRepository by lazy { SupabaseProfileRepository(supabase) }

    override val householdSession: HouseholdSession by lazy {
        HouseholdSession(authRepository, householdRepository, profileRepository, userPreferencesRepository, applicationScope)
    }

    override val syncEngine: SyncEngine by lazy {
        SyncEngine(
            store = RoomSyncStore(database),
            remote = SupabaseChoreRemote(supabase),
            scope = applicationScope,
            online = online,
            scheduleRetry = { SyncWorker.schedule(appContext) },
        )
    }

    override val syncController: SyncController get() = syncEngine

    override val choreRepository: ChoreRepository by lazy {
        OfflineChoreRepository(
            db = database,
            currentUserId = { (householdSession.state.value as? SessionState.Ready)?.user?.id },
            requestSync = syncEngine::requestSync,
        )
    }

    private val realtime by lazy {
        RealtimeSync(supabase, applicationScope) { householdId -> syncEngine.sync(householdId) }
    }

    private val foreground = MutableStateFlow(false)

    private val selectedHousehold by lazy {
        householdSession.state.map { (it as? SessionState.Ready)?.selected?.household?.id }.distinctUntilChanged()
    }

    init {
        applicationScope.launch {
            // Signing out removes this account's household data from the device.
            authRepository.authState.filter { it == AuthState.SignedOut }.collect {
                withContext(Dispatchers.IO) { database.clearAllTables() }
            }
        }
        // Live updates for the selected household while visible; catch up whenever it (re)appears.
        combine(foreground, selectedHousehold) { visible, household -> household.takeIf { visible } }
            .distinctUntilChanged()
            .onEach { household ->
                realtime.follow(household)
                if (household != null) syncEngine.requestSync(household)
            }
            .launchIn(applicationScope)
        if (BuildConfig.DEBUG) online.onEach { android.util.Log.d("BH", "online=$it") }.launchIn(applicationScope)
        // Back online: send anything queued and catch up.
        online.filter { it }.onEach {
            applicationScope.launch {
                val selected = (householdSession.state.value as? SessionState.Ready)?.selected?.household?.id
                syncEngine.syncAll(also = selected)
            }
        }.launchIn(applicationScope)
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
    }
}
