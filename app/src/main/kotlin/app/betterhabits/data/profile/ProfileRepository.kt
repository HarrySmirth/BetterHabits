package app.betterhabits.data.profile

import app.betterhabits.data.remote.backendCall
import app.betterhabits.data.remote.requireSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ProfileRepository {
    suspend fun profile(userId: String): Result<Profile>

    suspend fun updateDisplayName(userId: String, displayName: String): Result<Unit>

    /** Keeps the server-side timezone (used for schedules and summaries) in line with the device. */
    suspend fun updateTimezone(userId: String, timezone: String): Result<Unit>
}

@Serializable
private data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val timezone: String,
    @SerialName("is_child") val isChild: Boolean,
)

class SupabaseProfileRepository(private val client: SupabaseClient?) : ProfileRepository {

    private suspend fun db(): SupabaseClient = (client ?: throw AppException(AppError.NotConfigured)).requireSession()

    override suspend fun profile(userId: String) = backendCall {
        val dto = db().from("profiles").select { filter { eq("id", userId) } }.decodeSingle<ProfileDto>()
        Profile(dto.id, dto.displayName, dto.timezone, dto.isChild)
    }

    override suspend fun updateDisplayName(userId: String, displayName: String) = backendCall {
        db().from("profiles").update({ set("display_name", displayName.trim()) }) { filter { eq("id", userId) } }
        Unit
    }

    override suspend fun updateTimezone(userId: String, timezone: String) = backendCall {
        db().from("profiles").update({ set("timezone", timezone) }) { filter { eq("id", userId) } }
        Unit
    }
}
