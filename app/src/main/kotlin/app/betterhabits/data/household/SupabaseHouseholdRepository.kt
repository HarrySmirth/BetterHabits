package app.betterhabits.data.household

import app.betterhabits.data.remote.backendCall
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.ChildAccountCredentials
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdMember
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.HouseholdSummary
import app.betterhabits.domain.model.Invitation
import app.betterhabits.domain.model.InviteCode
import app.betterhabits.domain.model.JoinResult
import app.betterhabits.domain.model.PendingInvitation
import app.betterhabits.domain.model.PermissionOverride
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseHouseholdRepository(private val client: SupabaseClient?) : HouseholdRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun db(): SupabaseClient = client ?: throw AppException(AppError.NotConfigured)

    private suspend fun rpc(function: String, params: JsonObject = JsonObject(emptyMap())) =
        db().postgrest.rpc(function, params)

    override suspend fun myHouseholds(userId: String) = backendCall {
        db().from("household_members")
            .select(Columns.raw("role, households(id, name, timezone)")) { filter { eq("user_id", userId) } }
            .decodeList<MyMembershipDto>()
            .mapNotNull { dto -> dto.role.toRole()?.let { HouseholdSummary(dto.households.toDomain(), it) } }
            .sortedBy { it.household.name.lowercase() }
    }

    override suspend fun details(householdId: String, currentUserId: String) = backendCall {
        coroutineScope {
            val household = async {
                db().from("households").select { filter { eq("id", householdId) } }.decodeSingle<HouseholdDto>()
            }
            val members = async {
                db().from("household_members")
                    .select(Columns.raw("user_id, role, joined_at, profiles(display_name, is_child)")) {
                        filter { eq("household_id", householdId) }
                    }
                    .decodeList<MemberDto>()
            }
            val rolePermissions = async {
                db().from("household_role_permissions").select { filter { eq("household_id", householdId) } }
                    .decodeList<RolePermissionDto>()
            }
            val overrides = async {
                db().from("household_member_permission_overrides").select { filter { eq("household_id", householdId) } }
                    .decodeList<OverrideDto>()
            }
            HouseholdDetails(
                household = household.await().toDomain(),
                members = members.await().mapNotNull { dto ->
                    dto.role.toRole()?.let { role ->
                        HouseholdMember(dto.userId, dto.profiles.displayName, role, dto.profiles.isChild, parseTimestamp(dto.joinedAt))
                    }
                }.sortedWith(compareBy({ it.role.ordinal }, { it.displayName.lowercase() })),
                rolePermissions = rolePermissions.await()
                    .mapNotNull { dto -> dto.role.toRole()?.let { r -> dto.permission.toPermission()?.let { r to it } } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() },
                overrides = overrides.await().mapNotNull { dto ->
                    dto.permission.toPermission()?.let { PermissionOverride(dto.userId, it, dto.granted) }
                },
                currentUserId = currentUserId,
            )
        }
    }

    override suspend fun createHousehold(name: String, timezone: String) = backendCall {
        rpc("create_household", buildJsonObject { put("p_name", name.trim()); put("p_timezone", timezone) })
            .decodeAs<String>()
    }

    override suspend fun renameHousehold(householdId: String, name: String) = backendCall {
        db().from("households").update({ set("name", name.trim()) }) { filter { eq("id", householdId) } }
        Unit
    }

    override suspend fun deleteHousehold(householdId: String) = backendCall {
        db().from("households").delete { filter { eq("id", householdId) } }
        Unit
    }

    override suspend fun joinWithCode(code: String) = backendCall {
        val result = rpc("join_household_with_code", buildJsonObject { put("p_code", code) }).decodeAs<JoinResultDto>()
        when (result.status) {
            "joined" -> JoinResult.Joined(requireNotNull(result.householdId))
            "already_member" -> JoinResult.AlreadyMember(requireNotNull(result.householdId))
            "expired" -> throw AppException(AppError.InviteExpired)
            "rate_limited" -> throw AppException(AppError.RateLimited)
            else -> throw AppException(AppError.InviteInvalid)
        }
    }

    override suspend fun inviteCodes(householdId: String) = backendCall {
        db().from("household_invite_codes")
            .select {
                filter { eq("household_id", householdId) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<InviteCodeDto>()
            .map { it.toDomain() }
    }

    override suspend fun createInviteCode(householdId: String, validHours: Int) = backendCall {
        rpc("create_invite_code", buildJsonObject { put("p_household_id", householdId); put("p_valid_hours", validHours) })
            .decodeAs<InviteCodeDto>()
            .toDomain()
    }

    override suspend fun revokeInviteCode(codeId: String) = backendCall {
        rpc("revoke_invite_code", buildJsonObject { put("p_code_id", codeId) })
        Unit
    }

    override suspend fun pendingInvitations(householdId: String) = backendCall {
        db().from("household_invitations")
            .select {
                filter {
                    eq("household_id", householdId)
                    eq("status", "PENDING")
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<InvitationDto>()
            .mapNotNull { it.toDomain() }
    }

    override suspend fun inviteByEmail(householdId: String, email: String, role: HouseholdRole) = backendCall {
        rpc(
            "create_invitation",
            buildJsonObject {
                put("p_household_id", householdId)
                put("p_email", email.trim().lowercase())
                put("p_role", role.name)
            },
        ).decodeAs<InvitationDto>().toDomain() ?: throw AppException(AppError.Unknown())
    }

    override suspend fun revokeInvitation(invitationId: String) = backendCall {
        rpc("revoke_invitation", buildJsonObject { put("p_invitation_id", invitationId) })
        Unit
    }

    override suspend fun myPendingInvitations(): Result<List<PendingInvitation>> = backendCall {
        rpc("my_pending_invitations").decodeList<PendingInvitationDto>().mapNotNull { it.toDomain() }
    }

    override suspend fun respondToInvitation(invitationId: String, accept: Boolean) = backendCall {
        rpc("respond_to_invitation", buildJsonObject { put("p_invitation_id", invitationId); put("p_accept", accept) })
            .decodeAs<String>()
    }

    override suspend fun leave(householdId: String) = backendCall {
        rpc("leave_household", buildJsonObject { put("p_household_id", householdId) })
        Unit
    }

    override suspend fun removeMember(householdId: String, userId: String) = backendCall {
        rpc("remove_household_member", buildJsonObject { put("p_household_id", householdId); put("p_user_id", userId) })
        Unit
    }

    override suspend fun changeRole(householdId: String, userId: String, role: HouseholdRole) = backendCall {
        rpc(
            "change_member_role",
            buildJsonObject { put("p_household_id", householdId); put("p_user_id", userId); put("p_role", role.name) },
        )
        Unit
    }

    override suspend fun transferOwnership(householdId: String, newOwnerId: String) = backendCall {
        rpc("transfer_household_ownership", buildJsonObject { put("p_household_id", householdId); put("p_new_owner", newOwnerId) })
        Unit
    }

    override suspend fun setRolePermission(
        householdId: String,
        role: HouseholdRole,
        permission: HouseholdPermission,
        granted: Boolean,
    ) = backendCall {
        val table = db().from("household_role_permissions")
        if (granted) {
            table.upsert(RolePermissionDto(householdId, role.name, permission.name)) { ignoreDuplicates = true }
        } else {
            table.delete {
                filter {
                    eq("household_id", householdId)
                    eq("role", role.name)
                    eq("permission", permission.name)
                }
            }
        }
        Unit
    }

    override suspend fun setMemberOverride(
        householdId: String,
        userId: String,
        permission: HouseholdPermission,
        granted: Boolean?,
    ) = backendCall {
        val table = db().from("household_member_permission_overrides")
        if (granted == null) {
            table.delete {
                filter {
                    eq("household_id", householdId)
                    eq("user_id", userId)
                    eq("permission", permission.name)
                }
            }
        } else {
            table.upsert(OverrideDto(householdId, userId, permission.name, granted))
        }
        Unit
    }

    override suspend fun createChildAccount(householdId: String, displayName: String, pin: String) = backendCall {
        val response = db().functions.invoke(
            "child-accounts",
            buildJsonObject {
                put("action", "create")
                put("household_id", householdId)
                put("display_name", displayName.trim())
                put("pin", pin)
            },
        )
        val dto = json.decodeFromString<ChildCreatedDto>(response.bodyAsText())
        ChildAccountCredentials(dto.userId, dto.username)
    }

    override suspend fun resetChildPin(childUserId: String, pin: String) = backendCall {
        db().functions.invoke(
            "child-accounts",
            buildJsonObject { put("action", "reset_pin"); put("child_user_id", childUserId); put("pin", pin) },
        )
        Unit
    }

    override suspend fun deleteChildAccount(childUserId: String) = backendCall {
        db().functions.invoke("child-accounts", buildJsonObject { put("action", "delete"); put("child_user_id", childUserId) })
        Unit
    }
}
