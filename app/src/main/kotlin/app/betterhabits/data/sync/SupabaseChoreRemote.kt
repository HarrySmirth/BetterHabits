package app.betterhabits.data.sync

import app.betterhabits.data.chore.ChoreDto
import app.betterhabits.data.chore.OccurrenceDto
import app.betterhabits.data.household.parseTimestamp
import app.betterhabits.data.remote.backendCall
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Duration
import java.time.LocalDate

/** PostgREST implementation of [ChoreRemote]. All rules are enforced server-side (RLS + triggers). */
class SupabaseChoreRemote(private val client: SupabaseClient?) : ChoreRemote {

    private fun db(): SupabaseClient = client ?: throw AppException(AppError.NotConfigured)

    override suspend fun pullChores(householdId: String, since: String?): List<ChoreDto> = paged { from, to ->
        db().from("chores").select {
            filter {
                eq("household_id", householdId)
                if (since != null) gte("updated_at", overlap(since))
            }
            order("updated_at", Order.ASCENDING)
            range(from, to)
        }.decodeList<ChoreDto>()
    }

    override suspend fun pullOccurrences(householdId: String, since: String?, minDate: LocalDate): List<OccurrenceDto> = paged { from, to ->
        db().from("chore_occurrences").select {
            filter {
                eq("household_id", householdId)
                if (since != null) gte("updated_at", overlap(since)) else gte("occurrence_date", minDate.toString())
            }
            order("updated_at", Order.ASCENDING)
            range(from, to)
        }.decodeList<OccurrenceDto>()
    }

    override suspend fun push(op: PendingOp) {
        backendCall {
            when (op) {
                is PendingOp.CreateChore -> try {
                    db().from("chores").insert(op.row)
                } catch (e: PostgrestRestException) {
                    // Already inserted by an earlier attempt whose response was lost: that's success.
                    if (e.code != UNIQUE_VIOLATION) throw e
                }
                is PendingOp.UpdateChore ->
                    db().from("chores").update(op.fields) { filter { eq("id", op.choreId) } }
                is PendingOp.UpsertOccurrence ->
                    db().from("chore_occurrences").upsert(op.row) { onConflict = "chore_id,occurrence_date,occurrence_time" }
            }
            Unit
        }.getOrThrow()
    }

    /** Re-reads a small window before the cursor so rows committed late (same timestamp) aren't missed. */
    private fun overlap(cursor: String): String = parseTimestamp(cursor).minus(Duration.ofMinutes(2)).toString()

    private suspend fun <T> paged(fetch: suspend (from: Long, to: Long) -> List<T>): List<T> =
        backendCall {
            val all = mutableListOf<T>()
            var offset = 0L
            while (true) {
                val page = fetch(offset, offset + PAGE_SIZE - 1)
                all += page
                if (page.size < PAGE_SIZE) break
                offset += PAGE_SIZE
            }
            all.toList()
        }.getOrThrow()

    private companion object {
        const val PAGE_SIZE = 500
        const val UNIQUE_VIOLATION = "23505"
    }
}
