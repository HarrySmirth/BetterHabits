package app.betterhabits.domain.model

/**
 * Client-side mirror of the permission rules in supabase/migrations (private.has_permission,
 * can_manage_member, can_manage_role). Used only to decide what UI to show; the server enforces
 * the same rules through RLS and RPC checks regardless of what the client does.
 */
object HouseholdPermissions {

    fun hasPermission(
        role: HouseholdRole,
        permission: HouseholdPermission,
        rolePermissions: Map<HouseholdRole, Set<HouseholdPermission>>,
        memberOverrides: Map<HouseholdPermission, Boolean>,
    ): Boolean = when {
        role == HouseholdRole.OWNER -> true
        permission in memberOverrides -> memberOverrides.getValue(permission)
        else -> permission in rolePermissions[role].orEmpty()
    }

    /** Owners manage everyone but themselves; admins manage members and children. */
    fun canManageMember(myRole: HouseholdRole, targetRole: HouseholdRole): Boolean = when (myRole) {
        HouseholdRole.OWNER -> targetRole != HouseholdRole.OWNER
        HouseholdRole.ADMIN -> targetRole == HouseholdRole.MEMBER || targetRole == HouseholdRole.CHILD
        else -> false
    }

    /** Whether [myRole] may edit the permission set of [role]. */
    fun canManageRole(myRole: HouseholdRole, role: HouseholdRole): Boolean = when (myRole) {
        HouseholdRole.OWNER -> role != HouseholdRole.OWNER
        HouseholdRole.ADMIN -> role == HouseholdRole.MEMBER || role == HouseholdRole.CHILD
        else -> false
    }

    /** Roles [myRole] may give a member (ownership moves only through transfer). */
    fun assignableRoles(myRole: HouseholdRole, target: HouseholdMember): List<HouseholdRole> {
        if (!canManageMember(myRole, target.role)) return emptyList()
        if (target.isChildAccount) return listOf(HouseholdRole.CHILD)
        return listOf(HouseholdRole.ADMIN, HouseholdRole.MEMBER, HouseholdRole.CHILD)
            .filter { canManageRole(myRole, it) }
    }

    fun canRemove(details: HouseholdDetails, target: HouseholdMember): Boolean {
        val me = details.me ?: return false
        return target.userId != me.userId &&
            details.iCan(HouseholdPermission.REMOVE_MEMBERS) &&
            canManageMember(me.role, target.role)
    }

    fun canTransferOwnershipTo(details: HouseholdDetails, target: HouseholdMember): Boolean =
        details.me?.role == HouseholdRole.OWNER && target.userId != details.currentUserId && !target.isChildAccount

    fun canManageChildAccount(details: HouseholdDetails, target: HouseholdMember): Boolean =
        target.isChildAccount && details.iCan(HouseholdPermission.MANAGE_CHILDREN)

    fun canLeave(details: HouseholdDetails): Boolean {
        val me = details.me ?: return false
        return when (me.role) {
            HouseholdRole.CHILD -> false
            HouseholdRole.OWNER -> details.members.size == 1
            else -> true
        }
    }
}
