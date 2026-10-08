package app.betterhabits.data.chore

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.schedule.OccurrenceKey
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Chores and occurrence records for a household, offline-first.
 *
 * Reads are live streams of the local copy: they update when this device changes something, when a
 * sync pulls changes, or when realtime reports someone else's change. Writes apply locally at once
 * and are queued for the server; they only fail for local reasons. Server rejections surface through
 * [app.betterhabits.data.sync.SyncController] and are rolled back.
 */
interface ChoreRepository {
    /** Active and paused chores (not deleted). [zone] is the household timezone schedules use. */
    fun observeChores(householdId: String, zone: ZoneId): Flow<List<Chore>>

    /** Emits null if the chore doesn't exist locally or was deleted. */
    fun observeChore(choreId: String, zone: ZoneId): Flow<Chore?>

    /** Records for occurrences dated [from]..[to] (household-local dates). */
    fun observeRecords(householdId: String, from: LocalDate, to: LocalDate): Flow<List<OccurrenceRecord>>

    /** Completed/skipped records, newest first, optionally for one chore. */
    fun observeHistory(householdId: String, choreId: String? = null, limit: Int = 100): Flow<List<OccurrenceRecord>>

    suspend fun createChore(chore: Chore): Result<Unit>

    /** Sends only the fields that differ between [original] (as loaded) and [updated]. */
    suspend fun updateChore(original: Chore, updated: Chore): Result<Unit>

    /** Soft delete: history is kept. */
    suspend fun deleteChore(householdId: String, choreId: String): Result<Unit>

    suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String? = null): Result<Unit>

    suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey): Result<Unit>

    /** Back to pending (undo a completion or skip, or cancel a snooze). */
    suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey): Result<Unit>

    suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant): Result<Unit>

    /** Records which checklist steps are ticked for one occurrence (leaves its status alone). */
    suspend fun setCheckedSteps(householdId: String, choreId: String, key: OccurrenceKey, steps: Set<Int>): Result<Unit>

    /** Overrides the assignee for one occurrence; null = back to the chore's default. */
    suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?): Result<Unit>
}
