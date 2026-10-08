package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class MembershipManagementTest {

    private class Home(val id: UUID, val owner: TestUser, val admin: TestUser, val member: TestUser, val child: TestUser)

    private fun home(): Home {
        val owner = createUser(name = "Owner")
        val admin = createUser(name = "Admin")
        val member = createUser(name = "Member")
        val child = createUser(name = "Child", isChild = true)
        val id = owner.createHousehold()
        addMemberWithCode(id, owner, admin)
        addMemberWithCode(id, owner, member)
        asUser(owner) { query("select public.change_member_role(?, ?, 'ADMIN')", id, admin.id) }
        asAdmin { update("insert into public.household_members (household_id, user_id, role) values (?, ?, 'CHILD')", id, child.id) }
        return Home(id, owner, admin, member, child)
    }

    private fun hasPermission(user: TestUser, household: UUID, permission: String) =
        asUser(user) { scalar("select public.has_household_permission(?, ?::public.household_permission)", household, permission) as Boolean }

    @Test
    fun `default permissions by role`() {
        val h = home()
        assertEquals(true, hasPermission(h.owner, h.id, "MANAGE_CHILDREN"))
        assertEquals(true, hasPermission(h.admin, h.id, "REMOVE_MEMBERS"))
        assertEquals(true, hasPermission(h.member, h.id, "CREATE_CHORES"))
        assertEquals(false, hasPermission(h.member, h.id, "REMOVE_MEMBERS"))
        assertEquals(false, hasPermission(h.child, h.id, "CREATE_CHORES"))
    }

    @Test
    fun `member overrides take precedence over role defaults`() {
        val h = home()
        asUser(h.admin) {
            update(
                "insert into public.household_member_permission_overrides values (?, ?, 'CREATE_CHORES', true)",
                h.id, h.child.id,
            )
            update(
                "insert into public.household_member_permission_overrides values (?, ?, 'DELETE_CHORES', false)",
                h.id, h.member.id,
            )
        }
        assertEquals(true, hasPermission(h.child, h.id, "CREATE_CHORES"))
        assertEquals(false, hasPermission(h.member, h.id, "DELETE_CHORES"))
    }

    @Test
    fun `members cannot change permissions and admins cannot change admin permissions`() {
        val h = home()
        assertPermissionDenied {
            asUser(h.member) {
                update("insert into public.household_member_permission_overrides values (?, ?, 'REMOVE_MEMBERS', true)", h.id, h.member.id)
            }
        }
        assertPermissionDenied {
            asUser(h.admin) { update("insert into public.household_role_permissions values (?, 'ADMIN', 'MANAGE_SETTINGS')", h.id) }
        }
        asUser(h.admin) {
            assertEquals(0, update("delete from public.household_role_permissions where household_id = ? and role = 'ADMIN'", h.id))
            assertEquals(1, update("delete from public.household_role_permissions where household_id = ? and role = 'MEMBER' and permission = 'DELETE_CHORES'", h.id))
        }
        assertEquals(false, hasPermission(h.member, h.id, "DELETE_CHORES"))
    }

    @Test
    fun `removing members respects the role hierarchy`() {
        val h = home()
        assertPermissionDenied { asUser(h.member) { query("select public.remove_household_member(?, ?)", h.id, h.child.id) } }
        assertPermissionDenied { asUser(h.admin) { query("select public.remove_household_member(?, ?)", h.id, h.owner.id) } }

        asUser(h.admin) { query("select public.remove_household_member(?, ?)", h.id, h.member.id) }
        assertNull(roleOf(h.id, h.member))

        asUser(h.owner) { query("select public.remove_household_member(?, ?)", h.id, h.admin.id) }
        assertNull(roleOf(h.id, h.admin))
    }

    @Test
    fun `role changes respect the hierarchy and child accounts stay children`() {
        val h = home()
        assertPermissionDenied { asUser(h.admin) { query("select public.change_member_role(?, ?, 'ADMIN')", h.id, h.member.id) } }
        assertPermissionDenied { asUser(h.member) { query("select public.change_member_role(?, ?, 'CHILD')", h.id, h.admin.id) } }
        assertDbError("use_transfer_ownership") {
            asUser(h.owner) { query("select public.change_member_role(?, ?, 'OWNER')", h.id, h.admin.id) }
        }
        assertDbError("child_accounts_must_be_child_role") {
            asUser(h.owner) { query("select public.change_member_role(?, ?, 'MEMBER')", h.id, h.child.id) }
        }
        asUser(h.admin) { query("select public.change_member_role(?, ?, 'CHILD')", h.id, h.member.id) }
        assertEquals("CHILD", roleOf(h.id, h.member))
    }

    @Test
    fun `owner must transfer ownership before leaving a shared household`() {
        val h = home()
        assertDbError("transfer_ownership_required") { asUser(h.owner) { query("select public.leave_household(?)", h.id) } }
        assertDbError("children_cannot_own_households") {
            asUser(h.owner) { query("select public.transfer_household_ownership(?, ?)", h.id, h.child.id) }
        }

        asUser(h.owner) { query("select public.transfer_household_ownership(?, ?)", h.id, h.member.id) }
        assertEquals("OWNER", roleOf(h.id, h.member))
        assertEquals("ADMIN", roleOf(h.id, h.owner))

        asUser(h.owner) { query("select public.leave_household(?)", h.id) }
        assertNull(roleOf(h.id, h.owner))
    }

    @Test
    fun `children cannot leave and a sole owner leaving deletes the household`() {
        val h = home()
        assertPermissionDenied { asUser(h.child) { query("select public.leave_household(?)", h.id) } }

        val solo = createUser()
        val household = solo.createHousehold()
        asUser(solo) { query("select public.leave_household(?)", household) }
        asAdmin { assertEquals(0, count("select 1 from public.households where id = ?", household)) }
    }

    @Test
    fun `deleting an owner's auth user is blocked while the household has other members`() {
        val h = home()
        assertDbError("transfer_ownership_required") { asAdmin { update("delete from auth.users where id = ?", h.owner.id) } }
        assertEquals("OWNER", roleOf(h.id, h.owner))
    }

    @Test
    fun `account deletion prep removes solo households and refuses for shared ones`() {
        val h = home()
        val soloHousehold = h.owner.createHousehold("Just me")
        assertDbError("transfer_ownership_required") { asUser(h.owner) { query("select public.prepare_account_deletion()") } }

        val loner = createUser()
        val lonerHousehold = loner.createHousehold()
        asUser(loner) { query("select public.prepare_account_deletion()") }
        asAdmin {
            assertEquals(0, count("select 1 from public.households where id = ?", lonerHousehold))
            assertEquals(1, update("delete from auth.users where id = ?", loner.id))
            assertEquals(0, count("select 1 from public.profiles where id = ?", loner.id))
            assertEquals(1, count("select 1 from public.households where id = ?", soloHousehold))
        }
        assertPermissionDenied { asUser(h.child) { query("select public.prepare_account_deletion()") } }
    }

    @Test
    fun `deleting a household cascades to members, codes and invitations`() {
        val h = home()
        asUser(h.owner) {
            query("select public.create_invite_code(?)", h.id)
            query("select public.create_invitation(?, 'someone@example.test')", h.id)
        }
        assertEquals(0, asUser(h.admin) { update("delete from public.households where id = ?", h.id) })
        assertEquals(1, asUser(h.owner) { update("delete from public.households where id = ?", h.id) })
        asAdmin {
            assertEquals(0, count("select 1 from public.household_members where household_id = ?", h.id))
            assertEquals(0, count("select 1 from public.household_invite_codes where household_id = ?", h.id))
            assertEquals(0, count("select 1 from public.household_invitations where household_id = ?", h.id))
        }
    }
}
