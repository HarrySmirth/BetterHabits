package app.betterhabits.data.household

import app.betterhabits.domain.model.ChildAccountCredentials
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.HouseholdSummary
import app.betterhabits.domain.model.Invitation
import app.betterhabits.domain.model.InviteCode
import app.betterhabits.domain.model.JoinResult
import app.betterhabits.domain.model.PendingInvitation

/** Household membership, invitations and permissions. Every rule is enforced server-side. */
interface HouseholdRepository {
    suspend fun myHouseholds(userId: String): Result<List<HouseholdSummary>>

    suspend fun details(householdId: String, currentUserId: String): Result<HouseholdDetails>

    suspend fun createHousehold(name: String, timezone: String): Result<String>

    suspend fun renameHousehold(householdId: String, name: String): Result<Unit>

    suspend fun deleteHousehold(householdId: String): Result<Unit>

    // Joining
    suspend fun joinWithCode(code: String): Result<JoinResult>

    suspend fun inviteCodes(householdId: String): Result<List<InviteCode>>

    suspend fun createInviteCode(householdId: String, validHours: Int): Result<InviteCode>

    suspend fun revokeInviteCode(codeId: String): Result<Unit>

    suspend fun pendingInvitations(householdId: String): Result<List<Invitation>>

    suspend fun inviteByEmail(householdId: String, email: String, role: HouseholdRole): Result<Invitation>

    /** Emails the invitee (send-invitation Edge Function). The invitation exists either way. */
    suspend fun sendInvitationEmail(invitationId: String): Result<Unit>

    suspend fun revokeInvitation(invitationId: String): Result<Unit>

    suspend fun myPendingInvitations(): Result<List<PendingInvitation>>

    suspend fun respondToInvitation(invitationId: String, accept: Boolean): Result<String>

    // Membership
    suspend fun leave(householdId: String): Result<Unit>

    suspend fun removeMember(householdId: String, userId: String): Result<Unit>

    suspend fun changeRole(householdId: String, userId: String, role: HouseholdRole): Result<Unit>

    suspend fun transferOwnership(householdId: String, newOwnerId: String): Result<Unit>

    // Permissions
    suspend fun setRolePermission(householdId: String, role: HouseholdRole, permission: HouseholdPermission, granted: Boolean): Result<Unit>

    /** [granted] = null removes the override so the role default applies. */
    suspend fun setMemberOverride(householdId: String, userId: String, permission: HouseholdPermission, granted: Boolean?): Result<Unit>

    // Child accounts (Edge Function)
    suspend fun createChildAccount(householdId: String, displayName: String, pin: String): Result<ChildAccountCredentials>

    suspend fun resetChildPin(childUserId: String, pin: String): Result<Unit>

    suspend fun deleteChildAccount(childUserId: String): Result<Unit>
}
