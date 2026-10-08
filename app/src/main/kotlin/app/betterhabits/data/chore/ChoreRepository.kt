package app.betterhabits.data.chore

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.schedule.OccurrenceKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Chores and occurrence records for a household. Permission rules are enforced server-side. */
interface ChoreRepository {
    /** Active and paused chores (not deleted). [zone] is the household timezone the schedules use. */
    suspend fun chores(householdId: String, zone: ZoneId): Result<List<Chore>>

    suspend fun chore(choreId: String, zone: ZoneId): Result<Chore>

    /** Records for occurrences dated [from]..[to] (household-local dates). */
    suspend fun records(householdId: String, from: LocalDate, to: LocalDate): Result<List<OccurrenceRecord>>

    /** Completed/skipped records, newest first, optionally for one chore. */
    suspend fun history(householdId: String, choreId: String? = null, limit: Int = 100): Result<List<OccurrenceRecord>>

    suspend fun createChore(chore: Chore): Result<Unit>

    suspend fun updateChore(chore: Chore): Result<Unit>

    /** Soft delete: history is kept. */
    suspend fun deleteChore(choreId: String): Result<Unit>

    suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String? = null): Result<Unit>

    suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey): Result<Unit>

    /** Back to pending (undo a completion or skip, or cancel a snooze). */
    suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey): Result<Unit>

    suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant): Result<Unit>

    /** Overrides the assignee for one occurrence; null = back to the chore's default. */
    suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?): Result<Unit>
}
