package app.betterhabits.data.allocation

import app.betterhabits.data.local.CacheDao
import app.betterhabits.data.local.CacheEntity
import app.betterhabits.data.remote.backendCall
import app.betterhabits.data.remote.requireSession
import app.betterhabits.domain.allocation.AllocationSettings
import app.betterhabits.domain.allocation.Availability
import app.betterhabits.domain.allocation.DateRange
import app.betterhabits.domain.allocation.MemberPreferences
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.allocation.TimeOfDay
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.ChoreCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate

sealed interface PreferenceTarget {
    data class ChoreTarget(val choreId: String) : PreferenceTarget
    data class CategoryTarget(val category: ChoreCategory) : PreferenceTarget

    /** A household/personal template id or built-in key; applies to chores made from it. */
    data class TemplateTarget(val templateId: String) : PreferenceTarget
}

data class AwayPeriod(val id: String, val memberId: String, val range: DateRange, val note: String?)

/** Everything the allocator needs beyond chores and members. */
data class AllocationInputs(
    val preferences: Map<String, MemberPreferences>,
    val availability: Map<String, Availability>,
    val awayPeriods: List<AwayPeriod>,
    val settings: AllocationSettings,
) {
    fun preferencesOf(memberId: String) = preferences[memberId] ?: MemberPreferences(memberId)
    fun availabilityOf(memberId: String) = availability[memberId] ?: Availability(memberId)
}

/**
 * Preferences, availability and allocation settings. Edits need a connection (they're small,
 * rare, and permission-checked); reads fall back to the last copy so allocation works offline.
 */
interface AllocationRepository {
    suspend fun inputs(householdId: String): Result<AllocationInputs>
    suspend fun setPreference(householdId: String, userId: String, target: PreferenceTarget, level: PreferenceLevel?): Result<Unit>
    suspend fun setAvailability(householdId: String, userId: String, unavailableDays: Set<DayOfWeek>, preferredTimes: Set<TimeOfDay>): Result<Unit>
    suspend fun addAwayPeriod(householdId: String, userId: String, range: DateRange, note: String?): Result<Unit>
    suspend fun removeAwayPeriod(id: String): Result<Unit>
    suspend fun saveSettings(householdId: String, settings: AllocationSettings): Result<Unit>
    suspend fun setWorkloadShare(householdId: String, userId: String, share: Double): Result<Unit>
}

@Serializable
private data class PreferenceDto(
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("target_type") val targetType: String,
    val target: String,
    val level: String,
)

@Serializable
private data class AvailabilityDto(
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("unavailable_days") val unavailableDays: List<Int> = emptyList(),
    @SerialName("preferred_times") val preferredTimes: List<String> = emptyList(),
)

@Serializable
private data class AwayPeriodDto(
    val id: String? = null,
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val note: String? = null,
)

@Serializable
private data class SettingsDto(
    @SerialName("household_id") val householdId: String,
    @SerialName("preference_weight") val preferenceWeight: Int = AllocationSettings.DEFAULT_PREFERENCE_WEIGHT,
    @SerialName("allow_avoidance") val allowAvoidance: Boolean = false,
)

@Serializable
private data class CachedInputs(
    val preferences: List<PreferenceDto>,
    val availability: List<AvailabilityDto>,
    val away: List<AwayPeriodDto>,
    val settings: SettingsDto?,
)

class SupabaseAllocationRepository(
    private val client: SupabaseClient?,
    private val cache: CacheDao,
) : AllocationRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun db(): SupabaseClient = (client ?: throw AppException(AppError.NotConfigured)).requireSession()

    override suspend fun inputs(householdId: String): Result<AllocationInputs> {
        val key = "allocation:$householdId"
        val remote = backendCall {
            coroutineScope {
                val prefs = async { db().from("member_preferences").select { filter { eq("household_id", householdId) } }.decodeList<PreferenceDto>() }
                val avail = async { db().from("member_availability").select { filter { eq("household_id", householdId) } }.decodeList<AvailabilityDto>() }
                val away = async { db().from("member_away_periods").select { filter { eq("household_id", householdId) } }.decodeList<AwayPeriodDto>() }
                val settings = async {
                    db().from("household_allocation_settings").select { filter { eq("household_id", householdId) } }.decodeSingleOrNull<SettingsDto>()
                }
                CachedInputs(prefs.await(), avail.await(), away.await(), settings.await())
            }
        }
        remote.onSuccess { cache.put(CacheEntity(key, json.encodeToString(CachedInputs.serializer(), it))) }
        val raw = remote.getOrElse { e ->
            val error = e.appError
            if (error != AppError.Network && error != AppError.SessionExpired) return Result.failure(e)
            cache.get(key)?.let { json.decodeFromString(CachedInputs.serializer(), it) }
                ?: CachedInputs(emptyList(), emptyList(), emptyList(), null) // nothing cached yet: neutral defaults
        }
        return Result.success(raw.toDomain())
    }

    private fun CachedInputs.toDomain(): AllocationInputs {
        val prefsByMember = preferences.groupBy { it.userId }.mapValues { (memberId, rows) ->
            MemberPreferences(
                memberId = memberId,
                byChore = rows.filter { it.targetType == "CHORE" }.mapNotNull { r -> r.level.toLevel()?.let { r.target to it } }.toMap(),
                byCategory = rows.filter { it.targetType == "CATEGORY" }.mapNotNull { r ->
                    val category = ChoreCategory.entries.firstOrNull { it.name == r.target } ?: return@mapNotNull null
                    r.level.toLevel()?.let { category to it }
                }.toMap(),
                byTemplate = rows.filter { it.targetType == "TEMPLATE" }.mapNotNull { r -> r.level.toLevel()?.let { r.target to it } }.toMap(),
            )
        }
        val awayPeriods = away.mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            AwayPeriod(id, dto.userId, DateRange(LocalDate.parse(dto.startDate), LocalDate.parse(dto.endDate)), dto.note)
        }
        val members = (availability.map { it.userId } + awayPeriods.map { it.memberId }).toSet()
        val availabilityByMember = members.associateWith { memberId ->
            val row = availability.firstOrNull { it.userId == memberId }
            Availability(
                memberId = memberId,
                unavailableDays = row?.unavailableDays.orEmpty().map(DayOfWeek::of).toSet(),
                preferredTimes = row?.preferredTimes.orEmpty().mapNotNull { t -> TimeOfDay.entries.firstOrNull { it.name == t } }.toSet(),
                awayPeriods = awayPeriods.filter { it.memberId == memberId }.map { it.range },
            )
        }
        return AllocationInputs(
            preferences = prefsByMember,
            availability = availabilityByMember,
            awayPeriods = awayPeriods,
            settings = settings?.let { AllocationSettings(it.preferenceWeight.coerceIn(0, 100), it.allowAvoidance) } ?: AllocationSettings(),
        )
    }

    override suspend fun setPreference(householdId: String, userId: String, target: PreferenceTarget, level: PreferenceLevel?) = backendCall {
        val (type, value) = when (target) {
            is PreferenceTarget.ChoreTarget -> "CHORE" to target.choreId
            is PreferenceTarget.CategoryTarget -> "CATEGORY" to target.category.name
            is PreferenceTarget.TemplateTarget -> "TEMPLATE" to target.templateId
        }
        val table = db().from("member_preferences")
        if (level == null) {
            // Cleared: a chore falls back to its category's preference, a category to neutral.
            table.delete {
                filter {
                    eq("household_id", householdId)
                    eq("user_id", userId)
                    eq("target_type", type)
                    eq("target", value)
                }
            }
        } else {
            table.upsert(PreferenceDto(householdId, userId, type, value, level.name))
        }
        Unit
    }

    override suspend fun setAvailability(householdId: String, userId: String, unavailableDays: Set<DayOfWeek>, preferredTimes: Set<TimeOfDay>) = backendCall {
        db().from("member_availability").upsert(
            AvailabilityDto(householdId, userId, unavailableDays.map { it.value }.sorted(), preferredTimes.map { it.name }.sorted()),
        )
        Unit
    }

    override suspend fun addAwayPeriod(householdId: String, userId: String, range: DateRange, note: String?) = backendCall {
        db().from("member_away_periods").insert(
            AwayPeriodDto(householdId = householdId, userId = userId, startDate = range.start.toString(), endDate = range.end.toString(), note = note?.trim()?.ifEmpty { null }),
        )
        Unit
    }

    override suspend fun removeAwayPeriod(id: String) = backendCall {
        db().from("member_away_periods").delete { filter { eq("id", id) } }
        Unit
    }

    override suspend fun saveSettings(householdId: String, settings: AllocationSettings) = backendCall {
        db().from("household_allocation_settings").upsert(SettingsDto(householdId, settings.preferenceWeight, settings.allowAvoidance))
        Unit
    }

    override suspend fun setWorkloadShare(householdId: String, userId: String, share: Double) = backendCall {
        db().postgrest.rpc(
            "set_member_workload_share",
            buildJsonObject {
                put("p_household_id", householdId)
                put("p_user_id", userId)
                put("p_share", share)
            },
        )
        Unit
    }

    private fun String.toLevel(): PreferenceLevel? = PreferenceLevel.entries.firstOrNull { it.name == this }
}
