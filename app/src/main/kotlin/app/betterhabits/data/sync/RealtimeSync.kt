package app.betterhabits.data.sync

import android.util.Log
import app.betterhabits.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Listens for changes to the selected household's chores while the app is in the foreground, and
 * pulls them. The realtime payload is only a "something changed" signal: data always comes through
 * the normal (RLS-checked) pull, so there is one code path for applying server state.
 */
@OptIn(FlowPreview::class)
class RealtimeSync(
    private val client: SupabaseClient?,
    private val scope: CoroutineScope,
    private val onChanged: suspend (householdId: String) -> Unit,
) {
    private var channel: RealtimeChannel? = null
    private var job: Job? = null
    private var householdId: String? = null

    /** Subscribes to [householdId] (replacing any previous subscription); null stops listening. */
    fun follow(householdId: String?) {
        if (householdId == this.householdId) return
        stop()
        this.householdId = householdId
        val client = client ?: return
        if (householdId == null) return

        val newChannel = client.channel("household-$householdId")
        val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        fun changes(table: String): Flow<PostgresAction> = newChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            this.table = table
            filter("household_id", FilterOperator.EQ, householdId)
        }
        job = scope.launch {
            merge(changes("chores"), changes("chore_occurrences"))
                .onEach { nudges.tryEmit(Unit) }
                .launchIn(this)
            // Several changes in quick succession (e.g. a sync from another phone) become one pull.
            nudges.debounce(DEBOUNCE_MS).onEach { onChanged(householdId) }.launchIn(this)
            runCatching { newChannel.subscribe() }
                .onFailure { if (BuildConfig.DEBUG) Log.w("BH", "Realtime subscribe failed", it) }
        }
        channel = newChannel
    }

    fun stop() {
        job?.cancel()
        job = null
        val old = channel ?: return
        channel = null
        householdId = null
        client?.let { c -> scope.launch { runCatching { c.realtime.removeChannel(old) } } }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
