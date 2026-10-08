package app.betterhabits.testing

import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.ChildAccountCredentials
import app.betterhabits.domain.model.Household
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
import java.time.Instant
import java.util.UUID

/**
 * Small in-memory stand-in for the backend. Implements the happy paths and the main rules
 * (codes, roles); the authoritative rules are tested against Postgres in :supabase-tests.
 */
class FakeHouseholdRepository(private val currentUserId: () -> String?) : HouseholdRepository {

    private data class Member(val userId: String, var role: HouseholdRole, val isChild: Boolean = false)

    private val households = mutableMapOf<String, Household>()
    private val members = mutableMapOf<String, MutableList<Member>>()
    private val rolePermissions = mutableMapOf<String, MutableMap<HouseholdRole, MutableSet<HouseholdPermission>>>()
    private val overrides = mutableListOf<Pair<String, PermissionOverride>>()
    private val codes = mutableMapOf<String, Pair<String, InviteCode>>()
    private val invitations = mutableMapOf<String, Pair<String, Invitation>>()

    /** userId -> display name, shared with the fake profile repository. */
    val names = mutableMapOf<String, String>()
    val pendingForMe = mutableListOf<PendingInvitation>()
    var nextError: AppError? = null

    private fun me(): String = currentUserId() ?: throw AppException(AppError.SessionExpired)

    private inline fun <T> call(block: () -> T): Result<T> = try {
        nextError?.let {
            nextError = null
            throw AppException(it)
        }
        Result.success(block())
    } catch (e: AppException) {
        Result.failure(e)
    }

    /** Test helper: seeds a household owned by [ownerId] and returns its id. */
    fun seedHousehold(name: String, ownerId: String, vararg others: Pair<String, HouseholdRole>): String {
        val id = UUID.randomUUID().toString()
        households[id] = Household(id, name, "UTC")
        members[id] = (listOf(Member(ownerId, HouseholdRole.OWNER)) + others.map { Member(it.first, it.second, it.second == HouseholdRole.CHILD) })
            .toMutableList()
        rolePermissions[id] = defaultPermissions()
        return id
    }

    fun seedInviteCode(householdId: String, code: String, expiresAt: Instant = Instant.now().plusSeconds(3600)) {
        codes[code] = householdId to InviteCode(UUID.randomUUID().toString(), code, expiresAt, null, 0, false)
    }

    private fun defaultPermissions() = mutableMapOf(
        HouseholdRole.ADMIN to HouseholdPermission.entries.toMutableSet(),
        HouseholdRole.MEMBER to mutableSetOf(
            HouseholdPermission.CREATE_CHORES, HouseholdPermission.EDIT_CHORES, HouseholdPermission.DELETE_CHORES,
            HouseholdPermission.ASSIGN_CHORES, HouseholdPermission.MANAGE_TEMPLATES, HouseholdPermission.INVITE_MEMBERS,
        ),
        HouseholdRole.CHILD to mutableSetOf(),
    )

    override suspend fun myHouseholds(userId: String) = call {
        members.filterValues { list -> list.any { it.userId == userId } }.map { (id, list) ->
            HouseholdSummary(households.getValue(id), list.first { it.userId == userId }.role)
        }.sortedBy { it.household.name }
    }

    override suspend fun details(householdId: String, currentUserId: String) = call {
        val household = households[householdId] ?: throw AppException(AppError.PermissionDenied)
        HouseholdDetails(
            household = household,
            members = members.getValue(householdId).map {
                HouseholdMember(it.userId, names[it.userId] ?: it.userId, it.role, it.isChild, Instant.EPOCH)
            }.sortedBy { it.role.ordinal },
            rolePermissions = rolePermissions.getValue(householdId).mapValues { it.value.toSet() },
            overrides = overrides.filter { it.first == householdId }.map { it.second },
            currentUserId = currentUserId,
        )
    }

    override suspend fun createHousehold(name: String, timezone: String) = call { seedHousehold(name.trim(), me()) }

    override suspend fun renameHousehold(householdId: String, name: String) = call {
        households[householdId] = households.getValue(householdId).copy(name = name.trim())
    }

    override suspend fun deleteHousehold(householdId: String) = call {
        households.remove(householdId)
        members.remove(householdId)
        Unit
    }

    override suspend fun joinWithCode(code: String) = call<JoinResult> {
        val (householdId, invite) = codes[code] ?: throw AppException(AppError.InviteInvalid)
        if (!invite.isActive(Instant.now())) throw AppException(AppError.InviteExpired)
        val list = members.getValue(householdId)
        if (list.any { it.userId == me() }) {
            JoinResult.AlreadyMember(householdId)
        } else {
            list += Member(me(), HouseholdRole.MEMBER)
            JoinResult.Joined(householdId)
        }
    }

    override suspend fun inviteCodes(householdId: String) = call {
        codes.values.filter { it.first == householdId }.map { it.second }
    }

    override suspend fun createInviteCode(householdId: String, validHours: Int) = call {
        val code = InviteCode(UUID.randomUUID().toString(), "H7K4P9QX", Instant.now().plusSeconds(validHours * 3600L), null, 0, false)
        codes[code.code] = householdId to code
        code
    }

    override suspend fun revokeInviteCode(codeId: String) = call {
        codes.entries.removeIf { it.value.second.id == codeId }
        Unit
    }

    override suspend fun pendingInvitations(householdId: String) = call {
        invitations.values.filter { it.first == householdId }.map { it.second }
    }

    override suspend fun inviteByEmail(householdId: String, email: String, role: HouseholdRole) = call {
        val invitation = Invitation(UUID.randomUUID().toString(), email.trim().lowercase(), role, Instant.now().plusSeconds(86_400))
        invitations[invitation.id] = householdId to invitation
        invitation
    }

    val emailsSent = mutableListOf<String>()
    var failEmail = false

    override suspend fun sendInvitationEmail(invitationId: String) = call {
        if (failEmail) throw AppException(AppError.InviteEmailNotSent)
        emailsSent += invitationId
        Unit
    }

    override suspend fun revokeInvitation(invitationId: String) = call {
        invitations.remove(invitationId)
        Unit
    }

    override suspend fun myPendingInvitations() = call { pendingForMe.toList() }

    override suspend fun respondToInvitation(invitationId: String, accept: Boolean) = call {
        val invitation = pendingForMe.firstOrNull { it.id == invitationId } ?: throw AppException(AppError.InvitationNotFound)
        pendingForMe.remove(invitation)
        if (accept) members.getValue(invitation.householdId) += Member(me(), invitation.role)
        invitation.householdId
    }

    override suspend fun leave(householdId: String) = call {
        val list = members.getValue(householdId)
        val mine = list.first { it.userId == me() }
        if (mine.role == HouseholdRole.OWNER && list.size > 1) throw AppException(AppError.TransferOwnershipRequired(null))
        list.remove(mine)
        if (list.isEmpty()) {
            members.remove(householdId)
            households.remove(householdId)
        }
        Unit
    }

    override suspend fun removeMember(householdId: String, userId: String) = call {
        members.getValue(householdId).removeIf { it.userId == userId }
        Unit
    }

    override suspend fun changeRole(householdId: String, userId: String, role: HouseholdRole) = call {
        members.getValue(householdId).first { it.userId == userId }.role = role
    }

    override suspend fun transferOwnership(householdId: String, newOwnerId: String) = call {
        val list = members.getValue(householdId)
        list.first { it.userId == me() }.role = HouseholdRole.ADMIN
        list.first { it.userId == newOwnerId }.role = HouseholdRole.OWNER
    }

    override suspend fun setRolePermission(householdId: String, role: HouseholdRole, permission: HouseholdPermission, granted: Boolean) = call {
        val set = rolePermissions.getValue(householdId).getOrPut(role) { mutableSetOf() }
        if (granted) set += permission else set -= permission
        Unit
    }

    override suspend fun setMemberOverride(householdId: String, userId: String, permission: HouseholdPermission, granted: Boolean?) = call {
        overrides.removeIf { it.first == householdId && it.second.userId == userId && it.second.permission == permission }
        if (granted != null) overrides += householdId to PermissionOverride(userId, permission, granted)
        Unit
    }

    override suspend fun createChildAccount(householdId: String, displayName: String, pin: String) = call {
        val id = UUID.randomUUID().toString()
        names[id] = displayName
        members.getValue(householdId) += Member(id, HouseholdRole.CHILD, isChild = true)
        ChildAccountCredentials(id, displayName.lowercase() + "-ab12")
    }

    override suspend fun resetChildPin(childUserId: String, pin: String) = call { }

    override suspend fun deleteChildAccount(childUserId: String) = call {
        members.values.forEach { list -> list.removeIf { it.userId == childUserId } }
    }
}
