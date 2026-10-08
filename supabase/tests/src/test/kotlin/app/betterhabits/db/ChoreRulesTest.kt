package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ChoreRulesTest {

    private class Home(val id: UUID, val owner: TestUser, val member: TestUser, val child: TestUser)

    private fun home(): Home {
        val owner = createUser(name = "Owner")
        val member = createUser(name = "Member")
        val child = createUser(name = "Child", isChild = true)
        val id = owner.createHousehold()
        addMemberWithCode(id, owner, member)
        asAdmin { update("insert into public.household_members (household_id, user_id, role) values (?, ?, 'CHILD')", id, child.id) }
        return Home(id, owner, member, child)
    }

    private fun createChore(as_: TestUser, household: UUID, assignee: UUID? = null, recurrence: String = "DAILY"): UUID =
        asUser(as_) {
            scalar(
                """insert into public.chores (household_id, name, estimated_minutes, recurrence_type, recurrence_weekdays, start_date, assignee_id)
                   values (?, 'Dishes', 10, ?, case when ? = 'WEEKLY' then '{4}'::smallint[] else '{}' end, current_date, ?) returning id""",
                household, recurrence, recurrence, assignee,
            ) as UUID
        }

    private fun complete(as_: TestUser, chore: UUID, completedBy: UUID? = null, date: String = "2026-10-08") = asUser(as_) {
        query(
            """insert into public.chore_occurrences (chore_id, household_id, occurrence_date, status, completed_by)
               values (?, gen_random_uuid(), ?::date, 'COMPLETED', ?)
               on conflict (chore_id, occurrence_date, occurrence_time) do update
               set status = excluded.status, completed_by = excluded.completed_by
               returning completed_by, assignee_id, household_id""",
            chore, date, completedBy,
        ).single()
    }

    private fun revokeRolePermission(h: Home, role: String, permission: String) = asAdmin {
        update(
            "delete from public.household_role_permissions where household_id = ? and role = ?::public.household_role and permission = ?::public.household_permission",
            h.id, role, permission,
        )
    }

    @Test
    fun `members create chores, children can see but not create, outsiders see nothing`() {
        val h = home()
        val chore = createChore(h.member, h.id, assignee = h.member.id)
        asUser(h.child) { assertEquals(1, count("select 1 from public.chores where id = ?", chore)) }
        assertPermissionDenied { createChore(h.child, h.id) }
        asUser(createUser()) { assertEquals(0, count("select 1 from public.chores where id = ?", chore)) }
        asAdmin { assertEquals(h.member.id, scalar("select created_by from public.chores where id = ?", chore)) }
    }

    @Test
    fun `assigning needs ASSIGN_CHORES, editing needs EDIT_CHORES, and they are independent`() {
        val h = home()
        val chore = createChore(h.owner, h.id)
        revokeRolePermission(h, "MEMBER", "ASSIGN_CHORES")

        assertPermissionDenied { asUser(h.member) { update("update public.chores set assignee_id = ? where id = ?", h.member.id, chore) } }
        asUser(h.member) { assertEquals(1, update("update public.chores set name = 'Wash up' where id = ?", chore)) }

        revokeRolePermission(h, "MEMBER", "EDIT_CHORES")
        assertPermissionDenied { asUser(h.member) { update("update public.chores set estimated_minutes = 99 where id = ?", chore) } }
    }

    @Test
    fun `assignee must be a household member`() {
        val h = home()
        assertDbError("assignee_not_member") { createChore(h.owner, h.id, assignee = createUser().id) }
    }

    @Test
    fun `chores are soft-deleted with DELETE_CHORES and never hard-deleted by clients`() {
        val h = home()
        val chore = createChore(h.owner, h.id)
        revokeRolePermission(h, "MEMBER", "DELETE_CHORES")
        assertPermissionDenied { asUser(h.member) { update("update public.chores set deleted_at = now() where id = ?", chore) } }
        assertPermissionDenied { asUser(h.owner) { update("delete from public.chores where id = ?", chore) } }
        asUser(h.owner) { assertEquals(1, update("update public.chores set deleted_at = now() where id = ?", chore)) }
        assertDbError("chore_deleted") { complete(h.owner, chore) }
    }

    @Test
    fun `recurrence fields are validated`() {
        val h = home()
        assertDbError("chores_recurrence_fields") {
            asUser(h.owner) {
                update(
                    "insert into public.chores (household_id, name, estimated_minutes, recurrence_type, start_date) values (?, 'Bins', 10, 'WEEKLY', current_date)",
                    h.id,
                )
            }
        }
        createChore(h.owner, h.id, recurrence = "WEEKLY")
    }

    @Test
    fun `completion records who actually did it, which can differ from the assignee`() {
        val h = home()
        val chore = createChore(h.owner, h.id, assignee = h.owner.id)

        val own = complete(h.member, chore)
        assertEquals("defaults to the caller", h.member.id, own["completed_by"])
        assertNull("no per-occurrence override was set", own["assignee_id"])
        assertEquals("household copied from the chore, not the client", h.id, own["household_id"])

        // Members have EDIT_CHORES by default, so they may credit someone else...
        assertEquals(h.owner.id, complete(h.member, chore, completedBy = h.owner.id, date = "2026-10-09")["completed_by"])

        // ...but without it, completed_by is always forced to the caller.
        revokeRolePermission(h, "MEMBER", "EDIT_CHORES")
        assertEquals(h.member.id, complete(h.member, chore, completedBy = h.owner.id, date = "2026-10-11")["completed_by"])

        // Owners (EDIT_CHORES) can record that someone else did it.
        assertEquals(h.child.id, complete(h.owner, chore, completedBy = h.child.id, date = "2026-10-10")["completed_by"])
    }

    @Test
    fun `children can only complete occurrences assigned to them`() {
        val h = home()
        val theirs = createChore(h.owner, h.id, assignee = h.child.id)
        val notTheirs = createChore(h.owner, h.id, assignee = h.owner.id)
        assertEquals(h.child.id, complete(h.child, theirs)["completed_by"])
        assertPermissionDenied { complete(h.child, notTheirs) }
    }

    @Test
    fun `one row per occurrence, and undo resets the completion`() {
        val h = home()
        val chore = createChore(h.owner, h.id)
        complete(h.owner, chore)
        complete(h.owner, chore) // upsert, not a duplicate
        asUser(h.owner) {
            assertEquals(1, count("select 1 from public.chore_occurrences where chore_id = ?", chore))
            update("update public.chore_occurrences set status = 'PENDING' where chore_id = ?", chore)
            val row = query("select completed_by, completed_at from public.chore_occurrences where chore_id = ?", chore).single()
            assertNull(row["completed_by"])
            assertNull(row["completed_at"])
        }
        assertPermissionDenied { asUser(h.owner) { update("delete from public.chore_occurrences where chore_id = ?", chore) } }
    }

    @Test
    fun `completions cannot be dated in the future`() {
        val h = home()
        val chore = createChore(h.owner, h.id)
        assertDbError("completed_in_future") {
            asUser(h.owner) {
                update(
                    "insert into public.chore_occurrences (chore_id, household_id, occurrence_date, status, completed_at) values (?, ?, current_date, 'COMPLETED', now() + interval '1 day')",
                    chore, h.id,
                )
            }
        }
    }

    @Test
    fun `reassigning one occurrence needs ASSIGN_CHORES`() {
        val h = home()
        val chore = createChore(h.owner, h.id, assignee = h.owner.id)
        revokeRolePermission(h, "MEMBER", "ASSIGN_CHORES")
        assertPermissionDenied {
            asUser(h.member) {
                update("insert into public.chore_occurrences (chore_id, household_id, occurrence_date, assignee_id) values (?, ?, current_date, ?)", chore, h.id, h.member.id)
            }
        }
        asUser(h.owner) {
            update("insert into public.chore_occurrences (chore_id, household_id, occurrence_date, assignee_id) values (?, ?, current_date, ?)", chore, h.id, h.member.id)
        }
    }

    @Test
    fun `leaving a household unassigns that member's chores, even without ASSIGN_CHORES`() {
        val h = home()
        val chore = createChore(h.owner, h.id, assignee = h.member.id)
        revokeRolePermission(h, "MEMBER", "ASSIGN_CHORES")
        asUser(h.member) { query("select public.leave_household(?)", h.id) }
        asAdmin { assertNull(scalar("select assignee_id from public.chores where id = ?", chore)) }
    }

    @Test
    fun `deleting a member's account unassigns their chores`() {
        val h = home()
        val chore = createChore(h.owner, h.id, assignee = h.member.id)
        asAdmin {
            update("delete from auth.users where id = ?", h.member.id)
            assertNull(scalar("select assignee_id from public.chores where id = ?", chore))
        }
    }
}
