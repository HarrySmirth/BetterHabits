package app.betterhabits.testing

import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * In-memory chores with live flows, mirroring the offline repository's semantics (one record per
 * occurrence key, first completion wins). Tests seed and inspect state through the maps.
 */
class FakeChoreRepository(private val currentUserId: () -> String?) : ChoreRepository {

    private val choresState = MutableStateFlow<Map<String, Chore>>(emptyMap())
    private val deletedState = MutableStateFlow<Set<String>>(emptySet())
    private val recordsState = MutableStateFlow<Map<Pair<String, OccurrenceKey>, OccurrenceRecord>>(emptyMap())
    var nextError: AppError? = null

    /** Snapshot views for assertions. */
    val chores: Map<String, Chore> get() = choresState.value
    val records: Map<Pair<String, OccurrenceKey>, OccurrenceRecord> get() = recordsState.value

    fun putChore(chore: Chore) = choresState.update { it + (chore.id to chore) }

    fun putRecord(record: OccurrenceRecord) = recordsState.update { it + ((record.choreId to record.key) to record) }

    private inline fun call(block: () -> Unit): Result<Unit> = try {
        nextError?.let {
            nextError = null
            throw AppException(it)
        }
        block()
        Result.success(Unit)
    } catch (e: AppException) {
        Result.failure(e)
    }

    private fun upsert(choreId: String, key: OccurrenceKey, transform: (OccurrenceRecord) -> OccurrenceRecord) =
        recordsState.update { map ->
            val existing = map[choreId to key] ?: OccurrenceRecord(choreId, key, OccurrenceStatus.PENDING)
            map + ((choreId to key) to transform(existing))
        }

    override fun observeChores(householdId: String, zone: ZoneId): Flow<List<Chore>> =
        choresState.map { map -> map.values.filter { it.householdId == householdId && it.id !in deletedState.value }.sortedBy { it.name } }

    override fun observeChore(choreId: String, zone: ZoneId): Flow<Chore?> =
        choresState.map { it[choreId]?.takeIf { c -> c.id !in deletedState.value } }

    override fun observeRecords(householdId: String, from: LocalDate, to: LocalDate): Flow<List<OccurrenceRecord>> =
        recordsState.map { map ->
            map.values.filter { chores[it.choreId]?.householdId == householdId && !it.key.date.isBefore(from) && !it.key.date.isAfter(to) }
        }

    override fun observeHistory(householdId: String, choreId: String?, limit: Int): Flow<List<OccurrenceRecord>> =
        recordsState.map { map ->
            map.values
                .filter { chores[it.choreId]?.householdId == householdId && (choreId == null || it.choreId == choreId) }
                .filter { it.status != OccurrenceStatus.PENDING }
                .sortedByDescending { it.completedAt ?: Instant.EPOCH }
                .take(limit)
        }

    override suspend fun createChore(chore: Chore) = call { putChore(chore.copy(createdBy = currentUserId())) }

    override suspend fun updateChore(original: Chore, updated: Chore) = call {
        putChore(updated.copy(createdBy = chores[updated.id]?.createdBy))
    }

    override suspend fun deleteChore(householdId: String, choreId: String) = call {
        deletedState.update { it + choreId }
        choresState.update { it.toMap() } // re-emit
    }

    override suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String?) = call {
        upsert(choreId, key) {
            if (it.status == OccurrenceStatus.COMPLETED) it
            else it.copy(status = OccurrenceStatus.COMPLETED, completedBy = completedBy ?: currentUserId(), completedAt = completedAt, snoozedUntil = null)
        }
    }

    override suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.SKIPPED, completedBy = null, completedAt = null, snoozedUntil = null) }
    }

    override suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.PENDING, completedBy = null, completedAt = null, snoozedUntil = null) }
    }

    override suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.PENDING, completedBy = null, completedAt = null, snoozedUntil = until) }
    }

    override suspend fun setCheckedSteps(householdId: String, choreId: String, key: OccurrenceKey, steps: Set<Int>) = call {
        upsert(choreId, key) { it.copy(checkedSteps = steps) }
    }

    override suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?) = call {
        upsert(choreId, key) { it.copy(assigneeId = assigneeId) }
    }
}
