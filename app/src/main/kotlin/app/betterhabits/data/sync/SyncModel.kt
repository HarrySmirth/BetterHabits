package app.betterhabits.data.sync

import app.betterhabits.data.chore.ChoreDto
import app.betterhabits.data.chore.OccurrenceDto
import app.betterhabits.domain.error.AppError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** A local change queued for the server. Payloads are the exact JSON sent, so replays are faithful. */
@Serializable
sealed interface PendingOp {
    /** Insert a chore created on this device (client-generated id, so retries are idempotent). */
    @Serializable
    @SerialName("create_chore")
    data class CreateChore(val choreId: String, val row: JsonObject) : PendingOp

    /** Patch only the fields this device changed, so concurrent edits to other fields survive. */
    @Serializable
    @SerialName("update_chore")
    data class UpdateChore(val choreId: String, val fields: JsonObject) : PendingOp

    /** Upsert the given fields of one occurrence, keyed by (chore, date, time). */
    @Serializable
    @SerialName("upsert_occurrence")
    data class UpsertOccurrence(val row: JsonObject) : PendingOp
}

data class QueuedOp(val seq: Long, val op: PendingOp, val attempts: Int)

/** A change the server refused. It has been rolled back locally; the user should know. */
data class SyncProblem(val id: Long, val error: AppError)

data class SyncStatus(
    val online: Boolean = true,
    val syncing: Boolean = false,
    /** Local changes not yet confirmed by the server. */
    val pending: Int = 0,
    val problems: List<SyncProblem> = emptyList(),
    val lastSyncedAt: Instant? = null,
)

/** What screens use to show sync state and force a refresh. */
interface SyncController {
    val status: StateFlow<SyncStatus>

    /** Push local changes, then pull the latest, for one household. */
    suspend fun refresh(householdId: String): Result<Unit>

    fun dismissProblem(id: Long)
}

/** Local side of sync (Room in the app, in-memory in tests). */
interface SyncStore {
    val pendingCount: Flow<Int>

    suspend fun pendingOps(householdId: String): List<QueuedOp>

    suspend fun householdsWithPending(): List<String>

    /** Entity keys with queued changes; pulled rows for these are not applied until they're pushed. */
    suspend fun pendingKeys(householdId: String): Set<String>

    suspend fun removeOp(seq: Long)

    suspend fun recordFailedAttempt(seq: Long)

    suspend fun cursor(householdId: String, stream: String): String?

    suspend fun setCursor(householdId: String, stream: String, cursor: String)

    /** Forget sync progress so the next pull re-downloads everything (used after a rejected change). */
    suspend fun resetCursors(householdId: String)

    /** Drops the local version of an entity (after the server rejected a change to it). */
    suspend fun discardLocal(entityKey: String)

    suspend fun applyChores(householdId: String, rows: List<ChoreDto>, skipKeys: Set<String>)

    suspend fun applyOccurrences(householdId: String, rows: List<OccurrenceDto>, skipKeys: Set<String>)
}

/** Server side of sync. Throws AppException on failure. */
interface ChoreRemote {
    suspend fun pullChores(householdId: String, since: String?): List<ChoreDto>

    /** Rows changed since [since]; on a first sync (no cursor) only occurrences dated on/after [minDate]. */
    suspend fun pullOccurrences(householdId: String, since: String?, minDate: LocalDate): List<OccurrenceDto>

    suspend fun push(op: PendingOp)
}

/** The entity a queued op touches; matches [OutboxEntity.entityKey]. */
fun PendingOp.entityKey(): String = when (this) {
    is PendingOp.CreateChore -> EntityKeys.chore(choreId)
    is PendingOp.UpdateChore -> EntityKeys.chore(choreId)
    is PendingOp.UpsertOccurrence -> EntityKeys.occurrence(
        row.getValue("chore_id").jsonPrimitive.content,
        row.getValue("occurrence_date").jsonPrimitive.content,
        row["occurrence_time"]?.jsonPrimitive?.contentOrNull,
    )
}

object EntityKeys {
    fun chore(id: String) = "chore:$id"
    fun occurrence(choreId: String, date: String, time: String?) = "occurrence:$choreId|$date|${time.orEmpty()}"

    const val CHORES_STREAM = "chores"
    const val OCCURRENCES_STREAM = "occurrences"
}
