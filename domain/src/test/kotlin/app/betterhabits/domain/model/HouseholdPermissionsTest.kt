package app.betterhabits.domain.model

import app.betterhabits.domain.model.HouseholdPermission.CREATE_CHORES
import app.betterhabits.domain.model.HouseholdPermission.DELETE_CHORES
import app.betterhabits.domain.model.HouseholdPermission.MANAGE_CHILDREN
import app.betterhabits.domain.model.HouseholdPermission.REMOVE_MEMBERS
import app.betterhabits.domain.model.HouseholdRole.ADMIN
import app.betterhabits.domain.model.HouseholdRole.CHILD
import app.betterhabits.domain.model.HouseholdRole.MEMBER
import app.betterhabits.domain.model.HouseholdRole.OWNER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HouseholdPermissionsTest {

    private val defaults = mapOf(
        ADMIN to HouseholdPermission.entries.toSet(),
        MEMBER to setOf(CREATE_CHORES, DELETE_CHORES),
        CHILD to emptySet(),
    )

    private fun member(id: String, role: HouseholdRole, child: Boolean = role == CHILD) =
        HouseholdMember(id, id, role, child, Instant.EPOCH)

    private fun details(me: String, overrides: List<PermissionOverride> = emptyList()) = HouseholdDetails(
        household = Household("h", "Home", "UTC"),
        members = listOf(member("owner", OWNER), member("admin", ADMIN), member("member", MEMBER), member("kid", CHILD)),
        rolePermissions = defaults,
        overrides = overrides,
        currentUserId = me,
    )

    @Test
    fun `owner always has every permission, even with a deny override`() {
        assertTrue(HouseholdPermissions.hasPermission(OWNER, REMOVE_MEMBERS, emptyMap(), mapOf(REMOVE_MEMBERS to false)))
    }

    @Test
    fun `overrides beat role defaults in both directions`() {
        val d = details(
            me = "member",
            overrides = listOf(
                PermissionOverride("member", DELETE_CHORES, false),
                PermissionOverride("kid", CREATE_CHORES, true),
            ),
        )
        assertFalse(d.hasPermission("member", DELETE_CHORES))
        assertTrue(d.hasPermission("member", CREATE_CHORES))
        assertTrue(d.hasPermission("kid", CREATE_CHORES))
        assertFalse(d.hasPermission("kid", MANAGE_CHILDREN))
    }

    @Test
    fun `non-members have no permissions`() {
        assertFalse(details("owner").hasPermission("stranger", CREATE_CHORES))
    }

    @Test
    fun `management hierarchy matches the server`() {
        assertTrue(HouseholdPermissions.canManageMember(OWNER, ADMIN))
        assertFalse(HouseholdPermissions.canManageMember(OWNER, OWNER))
        assertTrue(HouseholdPermissions.canManageMember(ADMIN, CHILD))
        assertFalse(HouseholdPermissions.canManageMember(ADMIN, ADMIN))
        assertFalse(HouseholdPermissions.canManageMember(MEMBER, CHILD))
        assertFalse(HouseholdPermissions.canManageRole(ADMIN, ADMIN))
        assertTrue(HouseholdPermissions.canManageRole(ADMIN, MEMBER))
    }

    @Test
    fun `assignable roles respect hierarchy and child accounts`() {
        assertEquals(listOf(ADMIN, MEMBER, CHILD), HouseholdPermissions.assignableRoles(OWNER, member("m", MEMBER)))
        assertEquals(listOf(MEMBER, CHILD), HouseholdPermissions.assignableRoles(ADMIN, member("m", MEMBER)))
        assertEquals(listOf(CHILD), HouseholdPermissions.assignableRoles(OWNER, member("k", CHILD)))
        assertEquals(emptyList<HouseholdRole>(), HouseholdPermissions.assignableRoles(ADMIN, member("a", ADMIN)))
    }

    @Test
    fun `removal, transfer and leaving rules`() {
        val asAdmin = details("admin")
        assertTrue(HouseholdPermissions.canRemove(asAdmin, asAdmin.member("member")!!))
        assertFalse(HouseholdPermissions.canRemove(asAdmin, asAdmin.member("owner")!!))
        assertFalse(HouseholdPermissions.canRemove(asAdmin, asAdmin.member("admin")!!))

        val asOwner = details("owner")
        assertTrue(HouseholdPermissions.canTransferOwnershipTo(asOwner, asOwner.member("member")!!))
        assertFalse(HouseholdPermissions.canTransferOwnershipTo(asOwner, asOwner.member("kid")!!))
        assertFalse(HouseholdPermissions.canLeave(asOwner))
        assertTrue(HouseholdPermissions.canLeave(asAdmin))
        assertFalse(HouseholdPermissions.canLeave(details("kid")))
    }
}
