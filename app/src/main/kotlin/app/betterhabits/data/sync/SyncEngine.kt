package app.betterhabits.data.sync

import android.util.Log
import app.betterhabits.BuildConfig
import app.betterhabits.data.household.parseTimestamp
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.error.appError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * Keeps the local database and Supabase in step.
 *
 * Push: queued ops are sent in order. Transient failures (offline, expired session, rate limit)
 * stop the push and schedule a retry; the change stays queued. Rejections (e.g. permissions
 * changed while offline) drop the op, record a [SyncProblem] and force a full re-pull so the
 * local copy returns to the server's truth: nothing is lost silently.
 *
 * Pull: rows changed since the last cursor are applied, except rows with changes still queued
 * locally (those win until pushed, then the server's merged row comes back on the next pull).
 */
class SyncEngine(
    private val store: SyncStore,
    private val remote: ChoreRemote,
    private val scope: CoroutineScope,
    online: Flow<Boolean>,
    private val scheduleRetry: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : SyncController {

    private val mutex = Mutex()
    private val syncing = MutableStateFlow(false)
    private val problems = MutableStateFlow<List<SyncProblem>>(emptyList())
    private val lastSynced = MutableStateFlow<Instant?>(null)
    private var nextProblemId = 1L

    override val status: StateFlow<SyncStatus> =
        combine(online, syncing, store.pendingCount, problems, lastSynced) { isOnline, isSyncing, pending, list, last ->
            SyncStatus(isOnline, isSyncing, pending, list, last)
        }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    override suspend fun refresh(householdId: String): Result<Unit> = sync(householdId)

    override fun dismissProblem(id: Long) = problems.update { list -> list.filterNot { it.id == id } }

    /** Fire-and-forget sync after a local change; schedules a background retry if it can't finish. */
    fun requestSync(householdId: String) {
        scope.launch { sync(householdId) }
    }

    /** Syncs every household with queued changes (plus [also]). Used by the background worker. */
    suspend fun syncAll(also: String? = null): Result<Unit> {
        val households = (store.householdsWithPending() + listOfNotNull(also)).distinct()
        return households.map { sync(it) }.firstOrNull { it.isFailure } ?: Result.success(Unit)
    }

    suspend fun sync(householdId: String): Result<Unit> = mutex.withLock {
        syncing.value = true
        try {
            push(householdId)
            pull(householdId)
            lastSynced.value = clock.instant()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.appError
            if (BuildConfig.DEBUG) Log.w(TAG, "Sync of $householdId failed: $error", e)
            if (isTransient(error)) scheduleRetry()
            Result.failure(if (e is AppException) e else AppException(error, e))
        } finally {
            syncing.value = false
        }
    }

    private suspend fun push(householdId: String) {
        for (queued in store.pendingOps(householdId)) {
            try {
                remote.push(queued.op)
                store.removeOp(queued.seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val error = e.appError
                if (isTransient(error) && !(error is AppError.Unknown && queued.attempts + 1 >= MAX_UNKNOWN_ATTEMPTS)) {
                    store.recordFailedAttempt(queued.seq)
                    throw e // keep order: later ops wait for this one
                }
                // Rejected: drop it, tell the user, and re-download so the local copy matches the server.
                store.removeOp(queued.seq)
                store.discardLocal(queued.op.entityKey())
                store.resetCursors(householdId)
                problems.update { it + SyncProblem(nextProblemId++, error) }
                if (BuildConfig.DEBUG) Log.w(TAG, "Server rejected ${queued.op::class.simpleName}: $error", e)
            }
        }
    }

    private suspend fun pull(householdId: String) {
        val skip = store.pendingKeys(householdId)

        val choreCursor = store.cursor(householdId, EntityKeys.CHORES_STREAM)
        val chores = remote.pullChores(householdId, choreCursor)
        store.applyChores(householdId, chores, skip)
        chores.mapNotNull { it.updatedAt }.maxByOrNull(::parseTimestamp)?.let { store.setCursor(householdId, EntityKeys.CHORES_STREAM, it) }

        val occurrenceCursor = store.cursor(householdId, EntityKeys.OCCURRENCES_STREAM)
        val minDate = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(INITIAL_HISTORY_DAYS)
        val occurrences = remote.pullOccurrences(householdId, occurrenceCursor, minDate)
        store.applyOccurrences(householdId, occurrences, skip)
        occurrences.mapNotNull { it.updatedAt }.maxByOrNull(::parseTimestamp)?.let { store.setCursor(householdId, EntityKeys.OCCURRENCES_STREAM, it) }
    }

    private fun isTransient(error: AppError) = when (error) {
        AppError.Network, AppError.SessionExpired, AppError.RateLimited, is AppError.Unknown -> true
        else -> false
    }

    companion object {
        private const val TAG = "BH"
        /** Unrecognised errors are retried a few times, then treated as rejections so the queue can't jam. */
        const val MAX_UNKNOWN_ATTEMPTS = 5
        /** History downloaded on first sync of a household. */
        const val INITIAL_HISTORY_DAYS = 120L
    }
}
