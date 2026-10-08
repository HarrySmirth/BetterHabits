package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class AllocationRulesTest {

    private class Home(val id: UUID, val owner: TestUser, val member: TestUser, val child: TestUser)

    private fun home(): Home {
        val owner = createUser()
        val member = createUser()
        val child = createUser(isChild = true)
        val id = owner.createHousehold()
        addMemberWithCode(id, owner, member)
        asAdmin { update("insert into public.household_members (household_id, user_id, role) values (?, ?, 'CHILD')", id, child.id) }
        return Home(id, owner, member, child)
    }

    private fun setPreference(as_: TestUser, h: UUID, user: UUID, level: String = "HATE") = asUser(as_) {
        update(
            """insert into public.member_preferences (household_id, user_id, target_type, target, level)
               values (?, ?, 'CATEGORY', 'BATHROOM', ?)
               on conflict (household_id, user_id, target_type, target) do update set level = excluded.level""",
            h, user, level,
        )
    }

    @Test
    fun `members set their own preferences, everyone in the household can read them`() {
        val h = home()
        setPreference(h.member, h.id, h.member.id)
        asUser(h.owner) { assertEquals(1, count("select 1 from public.member_preferences where household_id = ?", h.id)) }
        asUser(createUser()) { assertEquals(0, count("select 1 from public.member_preferences where household_id = ?", h.id)) }
    }

    @Test
    fun `you can't set someone else's preferences, except a child's when you manage children`() {
        val h = home()
        assertPermissionDenied { setPreference(h.member, h.id, h.owner.id) }
        setPreference(h.owner, h.id, h.child.id) // owner can manage children
        assertPermissionDenied { setPreference(h.child, h.id, h.member.id) }
        setPreference(h.child, h.id, h.child.id, "LOVE") // children set their own
    }

    @Test
    fun `availability and away periods follow the same rules`() {
        val h = home()
        asUser(h.member) {
            update("insert into public.member_availability (household_id, user_id, unavailable_days, preferred_times) values (?, ?, '{1,2}', '{EVENING}')", h.id, h.member.id)
            update("insert into public.member_away_periods (household_id, user_id, start_date, end_date) values (?, ?, '2026-12-20', '2026-12-27')", h.id, h.member.id)
        }
        assertPermissionDenied {
            asUser(h.member) { update("insert into public.member_away_periods (household_id, user_id, start_date, end_date) values (?, ?, '2026-12-20', '2026-12-27')", h.id, h.owner.id) }
        }
        assertDbError("23514") { // check constraint: end before start
            asUser(h.member) { update("insert into public.member_away_periods (household_id, user_id, start_date, end_date) values (?, ?, '2026-12-27', '2026-12-20')", h.id, h.member.id) }
        }
    }

    @Test
    fun `allocation settings and fair shares need CONFIGURE_ALLOCATION`() {
        val h = home()
        assertPermissionDenied { asUser(h.member) { update("insert into public.household_allocation_settings (household_id, preference_weight) values (?, 80)", h.id) } }
        asUser(h.owner) { update("insert into public.household_allocation_settings (household_id, preference_weight) values (?, 80)", h.id) }
        asUser(h.member) { assertEquals(80, scalar("select preference_weight from public.household_allocation_settings where household_id = ?", h.id)) }

        assertPermissionDenied { asUser(h.member) { query("select public.set_member_workload_share(?, ?, 0.5)", h.id, h.child.id) } }
        asUser(h.owner) { query("select public.set_member_workload_share(?, ?, 0.5)", h.id, h.child.id) }
        asAdmin {
            assertEquals(BigDecimal("0.50"), scalar("select workload_share from public.household_members where household_id = ? and user_id = ?", h.id, h.child.id))
        }
        assertDbError("23514") { asUser(h.owner) { query("select public.set_member_workload_share(?, ?, 5)", h.id, h.child.id) } }
    }

    @Test
    fun `assignment controls on chores need ASSIGN_CHORES, not EDIT_CHORES`() {
        val h = home()
        val chore = asUser(h.owner) {
            scalar("insert into public.chores (household_id, name, estimated_minutes, recurrence_type, start_date) values (?, 'Bins', 10, 'DAILY', current_date) returning id", h.id) as UUID
        }
        asAdmin {
            update("delete from public.household_role_permissions where household_id = ? and role = 'MEMBER' and permission = 'ASSIGN_CHORES'", h.id)
        }
        assertPermissionDenied { asUser(h.member) { update("update public.chores set assignment_locked = true where id = ?", chore) } }
        assertPermissionDenied { asUser(h.member) { update("update public.chores set rotate = true where id = ?", chore) } }
        assertPermissionDenied { asUser(h.member) { update("update public.chores set excluded_member_ids = array[?]::uuid[] where id = ?", h.owner.id, chore) } }
        asUser(h.member) { assertEquals(1, update("update public.chores set name = 'Take bins out' where id = ?", chore)) }

        asAdmin {
            update("delete from public.household_role_permissions where household_id = ? and role = 'MEMBER' and permission = 'EDIT_CHORES'", h.id)
            update("insert into public.household_role_permissions values (?, 'MEMBER', 'ASSIGN_CHORES')", h.id)
        }
        asUser(h.member) { assertEquals(1, update("update public.chores set assignment_locked = true, assignment_source = 'AUTO', assignee_id = ? where id = ?", h.member.id, chore)) }
        assertPermissionDenied { asUser(h.member) { update("update public.chores set estimated_minutes = 99 where id = ?", chore) } }
    }

    @Test
    fun `leaving a household removes that member's preferences and availability`() {
        val h = home()
        setPreference(h.member, h.id, h.member.id)
        asUser(h.member) { query("select public.leave_household(?)", h.id) }
        asAdmin { assertEquals(0, count("select 1 from public.member_preferences where user_id = ?", h.member.id)) }
    }
}
