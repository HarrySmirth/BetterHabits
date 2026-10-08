package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class TemplateRulesTest {

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

    private fun householdTemplate(as_: TestUser, h: UUID, name: String = "Deep clean") = asUser(as_) {
        scalar(
            "insert into public.chore_templates (household_id, name, estimated_minutes, checklist) values (?, ?, 45, '{Toilet,Sink}') returning id",
            h, name,
        ) as UUID
    }

    private fun personalTemplate(as_: TestUser) = asUser(as_) {
        scalar("insert into public.chore_templates (owner_id, name, estimated_minutes) values (?, 'My routine', 15) returning id", as_.id) as UUID
    }

    @Test
    fun `household templates are shared with members and managed with MANAGE_TEMPLATES`() {
        val h = home()
        val t = householdTemplate(h.member, h.id)
        asUser(h.child) { assertEquals(1, count("select 1 from public.chore_templates where id = ?", t)) }
        asUser(createUser()) { assertEquals(0, count("select 1 from public.chore_templates where id = ?", t)) }

        assertPermissionDenied { householdTemplate(h.child, h.id) }
        asUser(h.child) { assertEquals(0, update("update public.chore_templates set name = 'Hacked' where id = ?", t)) }
        asUser(h.child) { assertEquals(0, update("delete from public.chore_templates where id = ?", t)) }

        asAdmin { update("delete from public.household_role_permissions where household_id = ? and role = 'MEMBER' and permission = 'MANAGE_TEMPLATES'", h.id) }
        asUser(h.member) { assertEquals(0, update("update public.chore_templates set name = 'Renamed' where id = ?", t)) }
        asUser(h.owner) { assertEquals(1, update("update public.chore_templates set name = 'Renamed' where id = ?", t)) }
        asUser(h.owner) { assertEquals(1, update("delete from public.chore_templates where id = ?", t)) }
    }

    @Test
    fun `personal templates are private to their owner`() {
        val h = home()
        val mine = personalTemplate(h.member)
        asUser(h.owner) { assertEquals(0, count("select 1 from public.chore_templates where id = ?", mine)) }
        asUser(h.owner) { assertEquals(0, update("delete from public.chore_templates where id = ?", mine)) }
        asUser(h.child) { personalTemplate(h.child) } // children can keep their own
        assertPermissionDenied {
            asUser(h.owner) { update("insert into public.chore_templates (owner_id, name, estimated_minutes) values (?, 'Sneaky', 5)", h.member.id) }
        }
        asUser(h.member) { assertEquals(1, update("update public.chore_templates set estimated_minutes = 20 where id = ?", mine)) }
    }

    @Test
    fun `a template has exactly one scope, which can't change`() {
        val h = home()
        assertDbError("23514") {
            asUser(h.owner) { update("insert into public.chore_templates (household_id, owner_id, name, estimated_minutes) values (?, ?, 'Both', 5)", h.id, h.owner.id) }
        }
        val shared = householdTemplate(h.owner, h.id)
        assertPermissionDenied {
            asUser(h.owner) { update("update public.chore_templates set household_id = null, owner_id = ? where id = ?", h.owner.id, shared) }
        }
        asAdmin { assertEquals(h.owner.id, scalar("select created_by from public.chore_templates where id = ?", shared)) }
    }

    @Test
    fun `chores keep their template id and occurrences record checked steps`() {
        val h = home()
        val chore = asUser(h.owner) {
            scalar(
                """insert into public.chores (household_id, name, estimated_minutes, recurrence_type, start_date, checklist, template_id, assignee_id)
                   values (?, 'Bathroom', 30, 'DAILY', current_date, '{Toilet,Sink,Mirror}', 'builtin.bathroom.deep_clean', ?) returning id""",
                h.id, h.child.id,
            ) as UUID
        }
        asUser(h.child) {
            update(
                """insert into public.chore_occurrences (chore_id, household_id, occurrence_date, checked_steps)
                   values (?, ?, current_date, '{0,2}')""",
                chore, h.id,
            )
        }
        asUser(h.member) { assertEquals("{0,2}", scalar("select checked_steps::text from public.chore_occurrences where chore_id = ?", chore)) }
        assertDbError("23514") {
            asUser(h.child) { update("update public.chore_occurrences set checked_steps = '{31}' where chore_id = ?", chore) }
        }
    }

    @Test
    fun `members can set template preferences`() {
        val h = home()
        asUser(h.member) {
            update(
                "insert into public.member_preferences (household_id, user_id, target_type, target, level) values (?, ?, 'TEMPLATE', 'builtin.garden.mow_lawn', 'LOVE')",
                h.id, h.member.id,
            )
        }
        asUser(h.owner) { assertEquals(1, count("select 1 from public.member_preferences where target_type = 'TEMPLATE'")) }
    }

    @Test
    fun `anonymous users can't read templates`() {
        val h = home()
        householdTemplate(h.owner, h.id)
        assertPermissionDenied { TestDatabase.asAnon { count("select 1 from public.chore_templates") } }
    }
}
