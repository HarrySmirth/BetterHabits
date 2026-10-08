package app.betterhabits.domain.model

import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import java.time.LocalDate
import java.time.ZoneId

/** Where a template lives: shipped with the app, shared by a household, or kept by one person. */
enum class TemplateScope { BUILT_IN, HOUSEHOLD, PERSONAL }

/** A template's default schedule. The day it falls on is chosen when the template is used. */
enum class RepeatKind { ONCE, DAILY, WEEKLY, MONTHLY }

/** A reusable chore definition that can be turned into a household chore. */
data class ChoreTemplate(
    /** A uuid for household/personal templates, or a stable key like "builtin.kitchen.clean_oven". */
    val id: String,
    val scope: TemplateScope,
    val name: String,
    val description: String? = null,
    val category: ChoreCategory = ChoreCategory.OTHER,
    val effort: Effort,
    val difficulty: Int = Chore.DEFAULT_DIFFICULTY,
    val points: Int = 0,
    val repeatKind: RepeatKind = RepeatKind.WEEKLY,
    val repeatInterval: Int = 1,
    val checklist: List<String> = emptyList(),
    /** Set for HOUSEHOLD templates. */
    val householdId: String? = null,
    /** Set for PERSONAL templates. */
    val ownerId: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= Chore.MAX_NAME_LENGTH) { "name must be 1..${Chore.MAX_NAME_LENGTH} characters" }
        require(difficulty in 1..5) { "difficulty must be 1..5" }
        require(effort.minutes in 1..Chore.MAX_MINUTES) { "estimated minutes must be 1..${Chore.MAX_MINUTES}" }
        require(repeatInterval in 1..365) { "repeat interval must be 1..365" }
        require(checklist.size <= MAX_STEPS) { "at most $MAX_STEPS steps" }
        require((scope == TemplateScope.HOUSEHOLD) == (householdId != null)) { "household templates need a household" }
        require((scope == TemplateScope.PERSONAL) == (ownerId != null)) { "personal templates need an owner" }
    }

    /** The default schedule, starting on [start] (weekly repeats fall on [start]'s weekday). */
    fun recurrence(start: LocalDate): Recurrence = when (repeatKind) {
        RepeatKind.ONCE -> Recurrence.Once
        RepeatKind.DAILY -> Recurrence.Daily(repeatInterval)
        RepeatKind.WEEKLY -> Recurrence.Weekly(setOf(start.dayOfWeek), repeatInterval)
        RepeatKind.MONTHLY -> Recurrence.MonthlyOnDay(start.dayOfMonth, repeatInterval)
    }

    /** A new, unassigned chore based on this template. */
    fun toChore(choreId: String, householdId: String, start: LocalDate, zone: ZoneId): Chore = Chore(
        id = choreId,
        householdId = householdId,
        name = name,
        description = description,
        category = category,
        effort = effort,
        difficulty = difficulty,
        points = points,
        schedule = Schedule(recurrence(start), start, zone = zone),
        checklist = checklist,
        templateId = id,
    )

    companion object {
        const val MAX_STEPS = 30
        const val BUILT_IN_PREFIX = "builtin."

        /** A template capturing [chore]'s definition (not its schedule dates or assignment). */
        fun fromChore(chore: Chore, id: String, scope: TemplateScope, householdId: String?, ownerId: String?): ChoreTemplate {
            val (kind, interval) = when (val r = chore.schedule.recurrence) {
                Recurrence.Once -> RepeatKind.ONCE to 1
                is Recurrence.Daily -> RepeatKind.DAILY to r.interval
                is Recurrence.Weekly -> RepeatKind.WEEKLY to r.interval
                is Recurrence.MonthlyOnDay -> RepeatKind.MONTHLY to r.interval
                is Recurrence.MonthlyOnWeekday -> RepeatKind.MONTHLY to r.interval
                is Recurrence.Yearly -> RepeatKind.MONTHLY to 12 * r.interval
            }
            return ChoreTemplate(
                id = id,
                scope = scope,
                name = chore.name,
                description = chore.description,
                category = chore.category,
                effort = chore.effort,
                difficulty = chore.difficulty,
                points = chore.points,
                repeatKind = kind,
                repeatInterval = interval.coerceIn(1, 365),
                checklist = chore.checklist,
                householdId = householdId,
                ownerId = ownerId,
            )
        }
    }
}
