package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAnon
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdIsolationTest {

    @Test
    fun `new auth user gets a profile from metadata`() {
        val harry = createUser(name = "Harry")
        val name = asUser(harry) { scalar("select display_name from public.profiles where id = ?", harry.id) }
        assertEquals("Harry", name)
    }

    @Test
    fun `creating a household makes the caller owner with default role permissions`() {
        val harry = createUser()
        val household = harry.createHousehold("Harry & Sarah")

        assertEquals("OWNER", roleOf(household, harry))
        asUser(harry) {
            assertEquals(1, count("select 1 from public.households where id = ?", household))
            val memberPerms = query(
                "select permission::text as p from public.household_role_permissions where household_id = ? and role = 'MEMBER'",
                household,
            ).map { it["p"] }.toSet()
            assertTrue("CREATE_CHORES" in memberPerms)
            assertFalse("REMOVE_MEMBERS" in memberPerms)
        }
    }

    @Test
    fun `non-members cannot see a household, its members, permissions or profiles`() {
        val harry = createUser(name = "Harry")
        val stranger = createUser(name = "Stranger")
        val household = harry.createHousehold()

        asUser(stranger) {
            assertEquals(0, count("select 1 from public.households where id = ?", household))
            assertEquals(0, count("select 1 from public.household_members where household_id = ?", household))
            assertEquals(0, count("select 1 from public.household_role_permissions where household_id = ?", household))
            assertEquals(0, count("select 1 from public.profiles where id = ?", harry.id))
            assertEquals(false, scalar("select public.has_household_permission(?, 'CREATE_CHORES')", household))
        }
    }

    @Test
    fun `household mates can see each other's profiles`() {
        val harry = createUser(name = "Harry")
        val sarah = createUser(name = "Sarah")
        val household = harry.createHousehold()
        addMemberWithCode(household, harry, sarah)

        asUser(sarah) {
            assertEquals("Harry", scalar("select display_name from public.profiles where id = ?", harry.id))
            assertEquals(2, count("select 1 from public.household_members where household_id = ?", household))
        }
    }

    @Test
    fun `anonymous callers see nothing and cannot call RPCs`() {
        val harry = createUser()
        harry.createHousehold()
        asAnon { assertPermissionDenied { query("select * from public.households") } }
        asAnon { assertPermissionDenied { query("select public.create_household('x')") } }
    }

    @Test
    fun `clients cannot write membership directly`() {
        val harry = createUser()
        val intruder = createUser()
        val household = harry.createHousehold()

        assertPermissionDenied {
            asUser(intruder) {
                update(
                    "insert into public.household_members (household_id, user_id, role) values (?, ?, 'OWNER')",
                    household, intruder.id,
                )
            }
        }
        assertPermissionDenied {
            asUser(harry) { update("update public.household_members set role = 'CHILD' where household_id = ?", household) }
        }
    }

    @Test
    fun `users can edit only their own name and timezone, never the child flag`() {
        val harry = createUser(name = "Harry")
        val sarah = createUser(name = "Sarah")
        val household = harry.createHousehold()
        addMemberWithCode(household, harry, sarah)

        asUser(harry) {
            assertEquals(1, update("update public.profiles set display_name = 'H', timezone = 'Europe/London' where id = ?", harry.id))
            assertEquals(0, update("update public.profiles set display_name = 'Hacked' where id = ?", sarah.id))
        }
        assertPermissionDenied { asUser(harry) { update("update public.profiles set is_child = true where id = ?", harry.id) } }
        assertDbError("invalid_timezone") {
            asUser(harry) { update("update public.profiles set timezone = 'Mars/Olympus' where id = ?", harry.id) }
        }
    }

    @Test
    fun `household settings require MANAGE_SETTINGS`() {
        val harry = createUser()
        val sarah = createUser()
        val household = harry.createHousehold("Old")
        addMemberWithCode(household, harry, sarah)

        asUser(sarah) { assertEquals(0, update("update public.households set name = 'Sarah''s' where id = ?", household)) }
        asUser(harry) { assertEquals(1, update("update public.households set name = 'New' where id = ?", household)) }
    }
}
