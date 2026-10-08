package app.betterhabits.testing

import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** In-memory chores. Mirrors the server's record semantics (one record per occurrence key). */
class FakeChoreRepository(private val currentUserId: () -> String?) : ChoreRepository {

    val chores = linkedMapOf<String, Chore>()
    val deleted = mutableSetOf<String>()
    val records = mutableMapOf<Pair<String, OccurrenceKey>, OccurrenceRecord>()
    var nextError: AppError? = null

    private inline fun <T> call(block: () -> T): Result<T> = try {
        nextError?.let {
            nextError = null
            throw AppException(it)
        }
        Result.success(block())
    } catch (e: AppException) {
        Result.failure(e)
    }

    private fun upsert(choreId: String, key: OccurrenceKey, transform: (OccurrenceRecord) -> OccurrenceRecord) {
        val existing = records[choreId to key] ?: OccurrenceRecord(choreId, key, OccurrenceStatus.PENDING)
        records[choreId to key] = transform(existing)
    }

    override suspend fun chores(householdId: String, zone: ZoneId) = call {
        chores.values.filter { it.householdId == householdId && it.id !in deleted }.sortedBy { it.name }
    }

    override suspend fun chore(choreId: String, zone: ZoneId) = call {
        chores[choreId] ?: throw AppException(AppError.PermissionDenied)
    }

    override suspend fun records(householdId: String, from: LocalDate, to: LocalDate) = call {
        records.values.filter { chores[it.choreId]?.householdId == householdId && !it.key.date.isBefore(from) && !it.key.date.isAfter(to) }
    }

    override suspend fun history(householdId: String, choreId: String?, limit: Int) = call {
        records.values
            .filter { chores[it.choreId]?.householdId == householdId && (choreId == null || it.choreId == choreId) }
            .filter { it.status != OccurrenceStatus.PENDING }
            .sortedByDescending { it.completedAt ?: Instant.EPOCH }
            .take(limit)
    }

    override suspend fun createChore(chore: Chore) = call { chores[chore.id] = chore.copy(createdBy = currentUserId()) }

    override suspend fun updateChore(chore: Chore) = call {
        chores[chore.id] = chore.copy(createdBy = chores[chore.id]?.createdBy)
    }

    override suspend fun deleteChore(choreId: String) = call {
        deleted += choreId
        Unit
    }

    override suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String?) = call {
        upsert(choreId, key) {
            it.copy(status = OccurrenceStatus.COMPLETED, completedBy = completedBy ?: currentUserId(), completedAt = completedAt, snoozedUntil = null)
        }
    }

    override suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.SKIPPED, completedBy = null, completedAt = null, snoozedUntil = null) }
    }

    override suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.PENDING, completedBy = null, completedAt = null, snoozedUntil = null) }
    }

    override suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant) = call {
        upsert(choreId, key) { it.copy(status = OccurrenceStatus.PENDING, snoozedUntil = until) }
    }

    override suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?) = call {
        upsert(choreId, key) { it.copy(assigneeId = assigneeId) }
    }
}
