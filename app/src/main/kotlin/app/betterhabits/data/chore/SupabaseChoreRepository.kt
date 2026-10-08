package app.betterhabits.data.chore

import app.betterhabits.data.remote.backendCall
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.schedule.OccurrenceKey
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SupabaseChoreRepository(private val client: SupabaseClient?) : ChoreRepository {

    private val json = Json { encodeDefaults = true }

    private fun db(): SupabaseClient = client ?: throw AppException(AppError.NotConfigured)

    override suspend fun chores(householdId: String, zone: ZoneId) = backendCall {
        db().from("chores")
            .select {
                filter {
                    eq("household_id", householdId)
                    exact("deleted_at", null)
                }
                order("name", Order.ASCENDING)
            }
            .decodeList<ChoreDto>()
            .mapNotNull { it.toDomain(zone) }
    }

    override suspend fun chore(choreId: String, zone: ZoneId) = backendCall {
        db().from("chores").select { filter { eq("id", choreId) } }.decodeSingle<ChoreDto>().toDomain(zone)
            ?: throw AppException(AppError.Unknown())
    }

    override suspend fun records(householdId: String, from: LocalDate, to: LocalDate) = backendCall {
        db().from("chore_occurrences")
            .select {
                filter {
                    eq("household_id", householdId)
                    gte("occurrence_date", from.toString())
                    lte("occurrence_date", to.toString())
                }
            }
            .decodeList<OccurrenceDto>()
            .mapNotNull { it.toDomain() }
    }

    override suspend fun history(householdId: String, choreId: String?, limit: Int) = backendCall {
        db().from("chore_occurrences")
            .select {
                filter {
                    eq("household_id", householdId)
                    isIn("status", listOf("COMPLETED", "SKIPPED"))
                    if (choreId != null) eq("chore_id", choreId)
                }
                order("updated_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<OccurrenceDto>()
            .mapNotNull { it.toDomain() }
    }

    override suspend fun createChore(chore: Chore) = backendCall {
        db().from("chores").insert(choreJson(chore, forInsert = true))
        Unit
    }

    override suspend fun updateChore(chore: Chore) = backendCall {
        db().from("chores").update(choreJson(chore, forInsert = false)) { filter { eq("id", chore.id) } }
        Unit
    }

    /** created_by is set by the server; id/household_id never change after creation. */
    private fun choreJson(chore: Chore, forInsert: Boolean): JsonObject {
        val all = json.encodeToJsonElement(ChoreDto.from(chore)).jsonObject
        val excluded = if (forInsert) setOf("created_by") else setOf("created_by", "id", "household_id")
        return JsonObject(all - excluded)
    }

    override suspend fun deleteChore(choreId: String) = backendCall {
        db().from("chores").update({ set("deleted_at", Instant.now().toString()) }) { filter { eq("id", choreId) } }
        Unit
    }

    override suspend fun complete(householdId: String, choreId: String, key: OccurrenceKey, completedAt: Instant, completedBy: String?) =
        upsertOccurrence(householdId, choreId, key) {
            put("status", "COMPLETED")
            put("completed_at", completedAt.toString())
            put("snoozed_until", JsonNull)
            if (completedBy != null) put("completed_by", completedBy)
        }

    override suspend fun skip(householdId: String, choreId: String, key: OccurrenceKey) =
        upsertOccurrence(householdId, choreId, key) {
            put("status", "SKIPPED")
            put("snoozed_until", JsonNull)
        }

    override suspend fun reset(householdId: String, choreId: String, key: OccurrenceKey) =
        upsertOccurrence(householdId, choreId, key) {
            put("status", "PENDING")
            put("snoozed_until", JsonNull)
        }

    override suspend fun snooze(householdId: String, choreId: String, key: OccurrenceKey, until: Instant) =
        upsertOccurrence(householdId, choreId, key) {
            put("status", "PENDING")
            put("snoozed_until", until.toString())
        }

    override suspend fun reassignOccurrence(householdId: String, choreId: String, key: OccurrenceKey, assigneeId: String?) =
        upsertOccurrence(householdId, choreId, key) {
            if (assigneeId == null) put("assignee_id", JsonNull) else put("assignee_id", assigneeId)
        }

    /** Writes only the given fields of the occurrence row identified by (chore, date, time). */
    private suspend fun upsertOccurrence(
        householdId: String,
        choreId: String,
        key: OccurrenceKey,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = backendCall {
        val row = buildJsonObject {
            put("chore_id", choreId)
            put("household_id", householdId)
            put("occurrence_date", key.date.toString())
            if (key.time == null) put("occurrence_time", JsonNull) else put("occurrence_time", key.time.toString())
            fields()
        }
        db().from("chore_occurrences").upsert(row) { onConflict = "chore_id,occurrence_date,occurrence_time" }
        Unit
    }
}
