package app.betterhabits.domain.model

import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Schedule
import java.time.Instant

/** Built-in categories. Stored as text so new ones can be added without a migration. */
enum class ChoreCategory { KITCHEN, BATHROOM, LIVING_AREAS, LAUNDRY, GARDEN, HOUSEHOLD, OTHER }

data class Chore(
    val id: String,
    val householdId: String,
    val name: String,
    val description: String? = null,
    val category: ChoreCategory = ChoreCategory.OTHER,
    val effort: Effort,
    /** 1 (easy) .. 5 (hard). */
    val difficulty: Int = DEFAULT_DIFFICULTY,
    val points: Int = 0,
    val schedule: Schedule,
    /** Default assignee for every occurrence; null = unassigned. Occurrences can override it. */
    val assigneeId: String? = null,
    val checklist: List<String> = emptyList(),
    val notes: String? = null,
    val requiresProof: Boolean = false,
    val active: Boolean = true,
    val createdBy: String? = null,
    /** Who chose the current assignee: a person, or the allocator. */
    val assignmentSource: AssignmentSource = AssignmentSource.MANUAL,
    /** Locked assignments are never changed by automatic allocation. */
    val assignmentLocked: Boolean = false,
    /** Members the allocator must never pick for this chore. */
    val excludedMemberIds: Set<String> = emptySet(),
    /** Take turns: after each completion the next person is chosen, balanced with workload. */
    val rotate: Boolean = false,
) {
    init {
        require(difficulty in 1..5) { "difficulty must be 1..5" }
        require(effort.minutes in 1..MAX_MINUTES) { "estimated minutes must be 1..$MAX_MINUTES" }
    }

    companion object {
        const val DEFAULT_DIFFICULTY = 3
        const val MAX_MINUTES = 24 * 60
        const val MAX_NAME_LENGTH = 80
    }
}

enum class AssignmentSource { MANUAL, AUTO }

enum class OccurrenceStatus { PENDING, COMPLETED, SKIPPED }

/**
 * What has happened to one occurrence. Only stored once something happens (completed, skipped,
 * snoozed or reassigned), so recurring chores never create rows in advance.
 */
data class OccurrenceRecord(
    val choreId: String,
    val key: OccurrenceKey,
    val status: OccurrenceStatus,
    /** Overrides the chore's default assignee for this occurrence only. */
    val assigneeId: String? = null,
    /** Who actually did it, which may differ from the assignee. */
    val completedBy: String? = null,
    val completedAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val note: String? = null,
)

/** Display state of an occurrence at a given moment. */
enum class OccurrenceState {
    UPCOMING,

    /** Pending and past due, and the chore's next occurrence hasn't become current yet. */
    OVERDUE,

    /** Pending past occurrence superseded by the next one: it was never done. */
    MISSED,
    SNOOZED,
    COMPLETED,
    SKIPPED,
}

/** One concrete occurrence of a chore, merged with any stored record. */
data class ChoreOccurrence(
    val chore: Chore,
    val key: OccurrenceKey,
    val dueAt: Instant,
    val record: OccurrenceRecord?,
    val state: OccurrenceState,
) {
    val assigneeId: String? get() = record?.assigneeId ?: chore.assigneeId
    val completedBy: String? get() = record?.completedBy
    val isDone: Boolean get() = state == OccurrenceState.COMPLETED || state == OccurrenceState.SKIPPED

    /** When it should next be shown as actionable: the snooze time if snoozed, else the due time. */
    val effectiveDueAt: Instant get() = record?.snoozedUntil?.takeIf { state == OccurrenceState.SNOOZED } ?: dueAt
}
