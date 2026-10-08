package app.betterhabits.data.household

import app.betterhabits.domain.model.Household
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.Invitation
import app.betterhabits.domain.model.InviteCode
import app.betterhabits.domain.model.PendingInvitation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// Wire formats for PostgREST/RPC. Enums travel as strings so unknown future values are skipped
// instead of crashing older app versions.

@Serializable
internal data class HouseholdDto(val id: String, val name: String, val timezone: String) {
    fun toDomain() = Household(id, name, timezone)
}

@Serializable
internal data class MyMembershipDto(val role: String, val households: HouseholdDto)

@Serializable
internal data class MemberProfileDto(@SerialName("display_name") val displayName: String, @SerialName("is_child") val isChild: Boolean)

@Serializable
internal data class MemberDto(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("workload_share") val workloadShare: Double = 1.0,
    val profiles: MemberProfileDto,
)

@Serializable
internal data class RolePermissionDto(
    @SerialName("household_id") val householdId: String,
    val role: String,
    val permission: String,
)

@Serializable
internal data class OverrideDto(
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String,
    val permission: String,
    val granted: Boolean,
)

@Serializable
internal data class InviteCodeDto(
    val id: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("use_count") val useCount: Int,
    @SerialName("revoked_at") val revokedAt: String? = null,
) {
    fun toDomain() = InviteCode(id, code, parseTimestamp(expiresAt), maxUses, useCount, revokedAt != null)
}

@Serializable
internal data class InvitationDto(
    val id: String,
    val email: String,
    val role: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    fun toDomain() = role.toRole()?.let { Invitation(id, email, it, parseTimestamp(expiresAt)) }
}

@Serializable
internal data class PendingInvitationDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("household_name") val householdName: String,
    @SerialName("invited_by_name") val invitedByName: String? = null,
    val role: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    fun toDomain() = role.toRole()?.let {
        PendingInvitation(id, householdId, householdName, invitedByName, it, parseTimestamp(expiresAt))
    }
}

@Serializable
internal data class JoinResultDto(val status: String, @SerialName("household_id") val householdId: String? = null)

@Serializable
internal data class ChildCreatedDto(@SerialName("user_id") val userId: String, val username: String)

internal fun String.toRole(): HouseholdRole? = HouseholdRole.entries.firstOrNull { it.name == this }

internal fun String.toPermission(): HouseholdPermission? = HouseholdPermission.entries.firstOrNull { it.name == this }

/** PostgREST timestamps may have 0-6 fractional digits and a +00:00 offset. */
internal fun parseTimestamp(value: String): Instant = java.time.OffsetDateTime.parse(value).toInstant()
