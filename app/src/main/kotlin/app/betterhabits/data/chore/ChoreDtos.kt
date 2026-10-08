package app.betterhabits.data.chore

import app.betterhabits.data.household.parseTimestamp
import app.betterhabits.domain.model.AssignmentSource
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Row shape of public.chores. Recurrence travels as typed columns, validated by the database. */
@Serializable
data class ChoreDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    val description: String? = null,
    val category: String = "OTHER",
    @SerialName("estimated_minutes") val estimatedMinutes: Int,
    val difficulty: Int = 3,
    val points: Int = 0,
    @SerialName("recurrence_type") val recurrenceType: String,
    @SerialName("recurrence_interval") val recurrenceInterval: Int = 1,
    @SerialName("recurrence_weekdays") val recurrenceWeekdays: List<Int> = emptyList(),
    @SerialName("recurrence_month_day") val recurrenceMonthDay: Int? = null,
    @SerialName("recurrence_month") val recurrenceMonth: Int? = null,
    @SerialName("recurrence_week_ordinal") val recurrenceWeekOrdinal: Int? = null,
    @SerialName("recurrence_weekday") val recurrenceWeekday: Int? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("times_of_day") val timesOfDay: List<String> = emptyList(),
    @SerialName("assignee_id") val assigneeId: String? = null,
    val checklist: List<String> = emptyList(),
    val notes: String? = null,
    @SerialName("requires_proof") val requiresProof: Boolean = false,
    val active: Boolean = true,
    @SerialName("assignment_source") val assignmentSource: String = "MANUAL",
    @SerialName("assignment_locked") val assignmentLocked: Boolean = false,
    @SerialName("excluded_member_ids") val excludedMemberIds: List<String> = emptyList(),
    val rotate: Boolean = false,
    @SerialName("created_by") val createdBy: String? = null,
    // Server-maintained; never sent by the client (see ChoreDto.writableJson).
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toDomain(zone: ZoneId): Chore? {
        val recurrence = runCatching { recurrence() }.getOrNull() ?: return null
        return Chore(
            id = id,
            householdId = householdId,
            name = name,
            description = description,
            category = ChoreCategory.entries.firstOrNull { it.name == category } ?: ChoreCategory.OTHER,
            effort = Effort(estimatedMinutes),
            difficulty = difficulty,
            points = points,
            schedule = Schedule(
                recurrence = recurrence,
                startDate = LocalDate.parse(startDate),
                endDate = endDate?.let(LocalDate::parse),
                timesOfDay = timesOfDay.map(LocalTime::parse),
                zone = zone,
            ),
            assigneeId = assigneeId,
            checklist = checklist,
            notes = notes,
            requiresProof = requiresProof,
            active = active,
            createdBy = createdBy,
            assignmentSource = AssignmentSource.entries.firstOrNull { it.name == assignmentSource } ?: AssignmentSource.MANUAL,
            assignmentLocked = assignmentLocked,
            excludedMemberIds = excludedMemberIds.toSet(),
            rotate = rotate,
        )
    }

    private fun recurrence(): Recurrence = when (recurrenceType) {
        "ONCE" -> Recurrence.Once
        "DAILY" -> Recurrence.Daily(recurrenceInterval)
        "WEEKLY" -> Recurrence.Weekly(recurrenceWeekdays.map(DayOfWeek::of).toSet(), recurrenceInterval)
        "MONTHLY_DAY" -> Recurrence.MonthlyOnDay(requireNotNull(recurrenceMonthDay), recurrenceInterval)
        "MONTHLY_WEEKDAY" -> Recurrence.MonthlyOnWeekday(
            requireNotNull(recurrenceWeekOrdinal), DayOfWeek.of(requireNotNull(recurrenceWeekday)), recurrenceInterval,
        )
        "YEARLY" -> Recurrence.Yearly(requireNotNull(recurrenceMonth), requireNotNull(recurrenceMonthDay), recurrenceInterval)
        else -> error("Unknown recurrence $recurrenceType")
    }

    companion object {
        fun from(chore: Chore): ChoreDto {
            val r = chore.schedule.recurrence
            return ChoreDto(
                id = chore.id,
                householdId = chore.householdId,
                name = chore.name.trim(),
                description = chore.description?.trim()?.ifEmpty { null },
                category = chore.category.name,
                estimatedMinutes = chore.effort.minutes,
                difficulty = chore.difficulty,
                points = chore.points,
                recurrenceType = when (r) {
                    Recurrence.Once -> "ONCE"
                    is Recurrence.Daily -> "DAILY"
                    is Recurrence.Weekly -> "WEEKLY"
                    is Recurrence.MonthlyOnDay -> "MONTHLY_DAY"
                    is Recurrence.MonthlyOnWeekday -> "MONTHLY_WEEKDAY"
                    is Recurrence.Yearly -> "YEARLY"
                },
                recurrenceInterval = r.interval,
                recurrenceWeekdays = (r as? Recurrence.Weekly)?.days?.map { it.value }?.sorted().orEmpty(),
                recurrenceMonthDay = when (r) {
                    is Recurrence.MonthlyOnDay -> r.dayOfMonth
                    is Recurrence.Yearly -> r.dayOfMonth
                    else -> null
                },
                recurrenceMonth = (r as? Recurrence.Yearly)?.month,
                recurrenceWeekOrdinal = (r as? Recurrence.MonthlyOnWeekday)?.ordinal,
                recurrenceWeekday = (r as? Recurrence.MonthlyOnWeekday)?.dayOfWeek?.value,
                startDate = chore.schedule.startDate.toString(),
                endDate = chore.schedule.endDate?.toString(),
                timesOfDay = chore.schedule.timesOfDay.sorted().distinct().map(LocalTime::toString),
                assigneeId = chore.assigneeId,
                checklist = chore.checklist.map(String::trim).filter(String::isNotEmpty),
                notes = chore.notes?.trim()?.ifEmpty { null },
                requiresProof = chore.requiresProof,
                active = chore.active,
                createdBy = null,
                assignmentSource = chore.assignmentSource.name,
                assignmentLocked = chore.assignmentLocked,
                excludedMemberIds = chore.excludedMemberIds.sorted(),
                rotate = chore.rotate,
            )
        }
    }
}

@Serializable
data class OccurrenceDto(
    @SerialName("chore_id") val choreId: String,
    @SerialName("occurrence_date") val occurrenceDate: String,
    @SerialName("occurrence_time") val occurrenceTime: String? = null,
    val status: String,
    @SerialName("assignee_id") val assigneeId: String? = null,
    @SerialName("completed_by") val completedBy: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    val note: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toDomain(): OccurrenceRecord? {
        val status = OccurrenceStatus.entries.firstOrNull { it.name == status } ?: return null
        return OccurrenceRecord(
            choreId = choreId,
            key = OccurrenceKey(LocalDate.parse(occurrenceDate), occurrenceTime?.let(LocalTime::parse)),
            status = status,
            assigneeId = assigneeId,
            completedBy = completedBy,
            completedAt = completedAt?.let(::parseTimestamp),
            snoozedUntil = snoozedUntil?.let(::parseTimestamp),
            note = note,
        )
    }
}
