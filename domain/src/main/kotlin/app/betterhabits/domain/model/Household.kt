package app.betterhabits.domain.model

import java.time.Instant

enum class HouseholdRole { OWNER, ADMIN, MEMBER, CHILD }

/** Mirrors the `household_permission` Postgres enum. Unknown server values are ignored by mappers. */
enum class HouseholdPermission {
    CREATE_CHORES,
    EDIT_CHORES,
    DELETE_CHORES,
    ASSIGN_CHORES,
    MANAGE_TEMPLATES,
    MANAGE_SETTINGS,
    INVITE_MEMBERS,
    REMOVE_MEMBERS,
    MANAGE_CHILDREN,
    CONFIGURE_ALLOCATION,
}

data class Household(
    val id: String,
    val name: String,
    val timezone: String,
)

/** A household as seen from the signed-in user's membership list. */
data class HouseholdSummary(
    val household: Household,
    val myRole: HouseholdRole,
)

data class HouseholdMember(
    val userId: String,
    val displayName: String,
    val role: HouseholdRole,
    val isChildAccount: Boolean,
    val joinedAt: Instant,
    /** Relative fair share of chores (1 = standard). */
    val workloadShare: Double = 1.0,
)

data class PermissionOverride(
    val userId: String,
    val permission: HouseholdPermission,
    val granted: Boolean,
)

/** Everything needed to render a household and decide what the current user may do in it. */
data class HouseholdDetails(
    val household: Household,
    val members: List<HouseholdMember>,
    val rolePermissions: Map<HouseholdRole, Set<HouseholdPermission>>,
    val overrides: List<PermissionOverride>,
    val currentUserId: String,
) {
    val me: HouseholdMember? get() = members.firstOrNull { it.userId == currentUserId }

    fun member(userId: String): HouseholdMember? = members.firstOrNull { it.userId == userId }

    fun overridesFor(userId: String): Map<HouseholdPermission, Boolean> =
        overrides.filter { it.userId == userId }.associate { it.permission to it.granted }

    fun hasPermission(userId: String, permission: HouseholdPermission): Boolean {
        val member = member(userId) ?: return false
        return HouseholdPermissions.hasPermission(member.role, permission, rolePermissions, overridesFor(userId))
    }

    fun iCan(permission: HouseholdPermission): Boolean = hasPermission(currentUserId, permission)
}

data class InviteCode(
    val id: String,
    val code: String,
    val expiresAt: Instant,
    val maxUses: Int?,
    val useCount: Int,
    val revoked: Boolean,
) {
    fun isActive(now: Instant): Boolean =
        !revoked && expiresAt.isAfter(now) && (maxUses == null || useCount < maxUses)
}

/** An email invitation, as seen by household inviters. */
data class Invitation(
    val id: String,
    val email: String,
    val role: HouseholdRole,
    val expiresAt: Instant,
)

/** An invitation addressed to the signed-in user. */
data class PendingInvitation(
    val id: String,
    val householdId: String,
    val householdName: String,
    val invitedByName: String?,
    val role: HouseholdRole,
    val expiresAt: Instant,
)

sealed interface JoinResult {
    val householdId: String

    data class Joined(override val householdId: String) : JoinResult
    data class AlreadyMember(override val householdId: String) : JoinResult
}

data class ChildAccountCredentials(val userId: String, val username: String)

data class Profile(
    val id: String,
    val displayName: String,
    val timezone: String,
    val isChild: Boolean,
)
