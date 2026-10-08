package app.betterhabits.data.chore

import app.betterhabits.data.household.parseTimestamp
import app.betterhabits.data.local.ChoreEntity
import app.betterhabits.data.local.OccurrenceEntity
import app.betterhabits.domain.model.OccurrenceRecord
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Mapping between wire DTOs, Room entities and domain models. */
internal object ChoreMapping {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Server-maintained columns the client never writes. */
    private val serverColumns = setOf("created_by", "updated_at", "deleted_at")

    /** Columns that never change after creation. */
    private val immutableColumns = setOf("id", "household_id")

    fun writableJson(dto: ChoreDto): JsonObject = JsonObject(json.encodeToJsonElement(dto).jsonObject - serverColumns)

    /** Only the columns whose values differ: what an edit actually changed. */
    fun changedFields(before: ChoreDto, after: ChoreDto): JsonObject {
        val old = writableJson(before)
        val new = writableJson(after)
        return JsonObject(new.filter { (key, value) -> key !in immutableColumns && old[key] != value })
    }

    fun decodeChore(entity: ChoreEntity): ChoreDto = json.decodeFromString(entity.json)

    fun choreEntity(dto: ChoreDto, deleted: Boolean = dto.deletedAt != null): ChoreEntity =
        ChoreEntity(dto.id, dto.householdId, json.encodeToString(ChoreDto.serializer(), dto), deleted, dto.updatedAt)

    fun occurrenceEntity(dto: OccurrenceDto, householdId: String): OccurrenceEntity = OccurrenceEntity(
        choreId = dto.choreId,
        date = dto.occurrenceDate,
        // Normalise "19:00:00" from the server to LocalTime's "19:00" so keys match local writes.
        time = dto.occurrenceTime?.let { LocalTime.parse(it).toString() }.orEmpty(),
        householdId = householdId,
        status = dto.status,
        assigneeId = dto.assigneeId,
        completedBy = dto.completedBy,
        completedAtMillis = dto.completedAt?.let { parseTimestamp(it).toEpochMilli() },
        snoozedUntilMillis = dto.snoozedUntil?.let { parseTimestamp(it).toEpochMilli() },
        note = dto.note,
        checkedSteps = encodeSteps(dto.checkedSteps),
        updatedAt = dto.updatedAt,
    )

    fun record(entity: OccurrenceEntity): OccurrenceRecord? {
        val status = OccurrenceStatus.entries.firstOrNull { it.name == entity.status } ?: return null
        return OccurrenceRecord(
            choreId = entity.choreId,
            key = key(entity),
            status = status,
            assigneeId = entity.assigneeId,
            completedBy = entity.completedBy,
            completedAt = entity.completedAtMillis?.let(Instant::ofEpochMilli),
            snoozedUntil = entity.snoozedUntilMillis?.let(Instant::ofEpochMilli),
            note = entity.note,
            checkedSteps = decodeSteps(entity.checkedSteps),
        )
    }

    fun key(entity: OccurrenceEntity) =
        OccurrenceKey(LocalDate.parse(entity.date), entity.time.takeIf { it.isNotEmpty() }?.let(LocalTime::parse))

    /** Room stores ticked step indexes as "0,2,3". */
    fun encodeSteps(steps: Collection<Int>): String = steps.toSortedSet().joinToString(",")

    fun decodeSteps(column: String): Set<Int> = column.split(',').mapNotNullTo(sortedSetOf()) { it.trim().toIntOrNull() }

    fun timeColumn(key: OccurrenceKey): String = key.time?.toString().orEmpty()

    /** The JSON row identifying one occurrence; callers add the fields they change. */
    fun occurrenceRow(householdId: String, choreId: String, key: OccurrenceKey, fields: JsonObject): JsonObject = buildJsonObject {
        put("chore_id", choreId)
        put("household_id", householdId)
        put("occurrence_date", key.date.toString())
        if (key.time == null) put("occurrence_time", JsonNull) else put("occurrence_time", key.time.toString())
        fields.forEach { (k, v) -> put(k, v) }
    }
}
