package app.betterhabits.data.sync

import androidx.room.withTransaction
import app.betterhabits.data.chore.ChoreDto
import app.betterhabits.data.chore.ChoreMapping
import app.betterhabits.data.chore.OccurrenceDto
import app.betterhabits.data.local.AppDatabase
import app.betterhabits.data.local.SyncCursorEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime

class RoomSyncStore(private val db: AppDatabase) : SyncStore {

    private val outbox = db.outboxDao()
    private val chores = db.choreDao()
    private val cursors = db.syncCursorDao()

    override val pendingCount: Flow<Int> = outbox.observeCount()

    override suspend fun pendingOps(householdId: String): List<QueuedOp> = outbox.ops(householdId).map {
        QueuedOp(it.seq, ChoreMapping.json.decodeFromString(PendingOp.serializer(), it.op), it.attempts)
    }

    override suspend fun householdsWithPending(): List<String> = outbox.householdsWithPending()

    override suspend fun pendingKeys(householdId: String): Set<String> = outbox.pendingKeys(householdId).toSet()

    override suspend fun removeOp(seq: Long) = outbox.delete(seq)

    override suspend fun recordFailedAttempt(seq: Long) = outbox.incrementAttempts(seq)

    override suspend fun cursor(householdId: String, stream: String): String? = cursors.cursor(householdId, stream)

    override suspend fun setCursor(householdId: String, stream: String, cursor: String) =
        cursors.set(SyncCursorEntity(householdId, stream, cursor))

    override suspend fun resetCursors(householdId: String) = cursors.clear(householdId)

    override suspend fun discardLocal(entityKey: String) {
        when {
            entityKey.startsWith("chore:") -> chores.deleteChore(entityKey.removePrefix("chore:"))
            entityKey.startsWith("occurrence:") -> {
                val (choreId, date, time) = entityKey.removePrefix("occurrence:").split("|")
                chores.deleteOccurrence(choreId, date, time)
            }
        }
    }

    override suspend fun applyChores(householdId: String, rows: List<ChoreDto>, skipKeys: Set<String>) {
        val entities = rows.filter { EntityKeys.chore(it.id) !in skipKeys }.map { ChoreMapping.choreEntity(it) }
        if (entities.isNotEmpty()) chores.upsertChores(entities)
    }

    override suspend fun applyOccurrences(householdId: String, rows: List<OccurrenceDto>, skipKeys: Set<String>) {
        val entities = rows
            .filter {
                val time = it.occurrenceTime?.let { t -> LocalTime.parse(t).toString() }
                EntityKeys.occurrence(it.choreId, it.occurrenceDate, time) !in skipKeys
            }
            .map { ChoreMapping.occurrenceEntity(it, householdId) }
        if (entities.isNotEmpty()) db.withTransaction { chores.upsertOccurrences(entities) }
    }
}
