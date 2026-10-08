package app.betterhabits.data.chore

import androidx.room.withTransaction
import app.betterhabits.data.local.AppDatabase
import app.betterhabits.data.local.OccurrenceEntity
import app.betterhabits.data.local.OutboxEntity
import app.betterhabits.data.sync.entityKey
import app.betterhabits.data.sync.PendingOp
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Room-backed [ChoreRepository]. Each write updates the local copy and appends a [PendingOp] to the
 * outbox in one transaction, then asks the sync engine to push. Local updates mirror the server's
 * rules (completion fields, first completion wins) so the UI shows what the server will store.
 */
class OfflineChoreRepository(
    private val db: AppDatabase,
    private val currentUserId: () -> String?,
    private val requestSync: (householdId: String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : ChoreRepository {

    private val dao = db.choreDao()
    private val json = ChoreMapping.json

    override fun observeChores(householdId: String, zone: ZoneId): Flow<List<Chore>> =
        dao.observeChores(householdId)
            .map { list -> list.mapNotNull { ChoreMapping.decodeChore(it).toDomain(zone) }.sortedBy { it.name.lowercase() } }
            .distinctUntilChanged()

    override fun observeChore(choreId: String, zone: ZoneId): Flow<Chore?> =
        dao.observeChore(choreId)
            .map { entity -> entity?.takeUnless { it.deleted }?.let { ChoreMapping.decodeChore(it).toDomain(zone) } }
            .distinctUntilChanged()

    override fun observeRecords(householdId: String, from: LocalDate, to: LocalDate): Flow<List<OccurrenceRecord>> =
        dao.observeOccurrences(householdId, from.toString(), to.toString())
            .map { list -> list.mapNotNull(ChoreMapping::record) }
            .distinctUntilChanged()

    override fun observeHistory(householdId: String, choreId: String?, limit: Int): Flow<List<OccurrenceRecord>> =
        dao.observeHistory(householdId, choreId, limit)
            .map { list -> list.mapNotNull(ChoreMapping::record) }
            .distinctUntilChanged()

    override suspend fun createChore(chore: Chore): Result<Unit> = write(chore.householdId) {
        val dto = ChoreDto.from(chore).copy(createdBy = currentUserId())
        dao.upsertChores(listOf(ChoreMapping.choreEntity(dto, deleted = false).copy(updatedAt = null)))
        enqueue(chore.householdId, PendingOp.CreateChore(chore.id, ChoreMapping.writableJson(dto)))
    }

    override suspend fun updateChore(original: Chore, updated: Chore): Result<Unit> = write(updated.householdId) {
        val changed = ChoreMapping.changedFields(ChoreDto.from(original), ChoreDto.from(updated))
        if (changed.isEmpty()) return@write
        // Keep server-maintained fields (created_by, updated_at) from the stored row.
        val stored = dao.chore(updated.id)?.let(ChoreMapping::decodeChore)
        val dto = ChoreDto.from(updated).copy(createdBy = stored?.createdBy, updatedAt = stored?.updatedAt)
        dao.upsertChores(listOf(ChoreMapping.choreEntity(dto, deleted = false)))
        enqueue(updated.householdId, PendingOp.UpdateChore(updated.id, changed))
    }

    override suspend fun deleteChore(householdId: String, choreId: String): Result<Unit> = write(householdId) {
        val stored = dao.chore(choreId) ?: return@write
        dao.upsertChores(listOf(stored.copy(deleted = true)))
        enqueue(householdId, PendingOp.UpdateChore(choreId, buildJsonObject { put("deleted_at", clock.instant().toString()) }))
    }

    override suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String?) =
        updateOccurrence(householdId, choreId, key, buildJsonObject {
            put("status", OccurrenceStatus.COMPLETED.name)
            put("completed_at", completedAt.toString())
            put("snoozed_until", JsonNull)
            if (completedBy != null) put("completed_by", completedBy)
        }) { existing ->
            if (existing?.status == OccurrenceStatus.COMPLETED.name) {
                existing // first completion wins, as on the server
            } else {
                base(existing, householdId, choreId, key).copy(
                    status = OccurrenceStatus.COMPLETED.name,
                    completedBy = completedBy ?: currentUserId(),
                    completedAtMillis = completedAt.toEpochMilli(),
                    snoozedUntilMillis = null,
                )
            }
        }

    override suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey) =
        updateOccurrence(householdId, choreId, key, buildJsonObject {
            put("status", OccurrenceStatus.SKIPPED.name)
            put("snoozed_until", JsonNull)
        }) { base(it, householdId, choreId, key).cleared(OccurrenceStatus.SKIPPED) }

    override suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey) =
        updateOccurrence(householdId, choreId, key, buildJsonObject {
            put("status", OccurrenceStatus.PENDING.name)
            put("snoozed_until", JsonNull)
        }) { base(it, householdId, choreId, key).cleared(OccurrenceStatus.PENDING) }

    override suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant) =
        updateOccurrence(householdId, choreId, key, buildJsonObject {
            put("status", OccurrenceStatus.PENDING.name)
            put("snoozed_until", until.toString())
        }) { base(it, householdId, choreId, key).cleared(OccurrenceStatus.PENDING).copy(snoozedUntilMillis = until.toEpochMilli()) }

    override suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?) =
        updateOccurrence(householdId, choreId, key, buildJsonObject {
            if (assigneeId == null) put("assignee_id", JsonNull) else put("assignee_id", assigneeId)
        }) { base(it, householdId, choreId, key).copy(assigneeId = assigneeId) }

    private fun base(existing: OccurrenceEntity?, householdId: String, choreId: String, key: OccurrenceKey) = existing ?: OccurrenceEntity(
        choreId = choreId,
        date = key.date.toString(),
        time = ChoreMapping.timeColumn(key),
        householdId = householdId,
        status = OccurrenceStatus.PENDING.name,
        assigneeId = null,
        completedBy = null,
        completedAtMillis = null,
        snoozedUntilMillis = null,
        note = null,
        updatedAt = null,
    )

    /** Mirrors the server trigger: leaving COMPLETED clears who/when and any snooze. */
    private fun OccurrenceEntity.cleared(status: OccurrenceStatus) =
        copy(status = status.name, completedBy = null, completedAtMillis = null, snoozedUntilMillis = null)

    private suspend fun updateOccurrence(
        householdId: String,
        choreId: String,
        key: OccurrenceKey,
        fields: JsonObject,
        transform: (OccurrenceEntity?) -> OccurrenceEntity,
    ): Result<Unit> = write(householdId) {
        val existing = dao.occurrence(choreId, key.date.toString(), ChoreMapping.timeColumn(key))
        dao.upsertOccurrences(listOf(transform(existing)))
        enqueue(householdId, PendingOp.UpsertOccurrence(ChoreMapping.occurrenceRow(householdId, choreId, key, fields)))
    }

    private suspend fun enqueue(householdId: String, op: PendingOp) {
        db.outboxDao().insert(
            OutboxEntity(
                householdId = householdId,
                op = json.encodeToString(PendingOp.serializer(), op),
                entityKey = op.entityKey(),
                createdAtMillis = clock.millis(),
            ),
        )
    }

    private suspend fun write(householdId: String, block: suspend () -> Unit): Result<Unit> {
        try {
            db.withTransaction { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(AppException(AppError.Unknown(e), e))
        }
        requestSync(householdId)
        return Result.success(Unit)
    }
}
