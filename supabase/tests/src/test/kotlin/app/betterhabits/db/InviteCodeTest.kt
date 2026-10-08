package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InviteCodeTest {

    private fun createCode(user: TestUser, household: UUID, maxUses: Int? = null): Map<String, Any?> =
        asUser(user) { query("select * from public.create_invite_code(?, 72, ?)", household, maxUses).single() }

    private fun join(user: TestUser, code: String): Map<String, Any?> = asUser(user) {
        query(
            "select r ->> 'status' as status, r ->> 'household_id' as household from public.join_household_with_code(?) r",
            code,
        ).single()
    }

    @Test
    fun `codes are 8 characters from the unambiguous alphabet`() {
        val harry = createUser()
        val household = harry.createHousehold()
        repeat(20) {
            val code = createCode(harry, household)["code"] as String
            assertTrue(code, code.matches(Regex("^[2-9ABCDEFGHJKMNPQRSTUVWXYZ]{8}$")))
        }
    }

    @Test
    fun `joining with a valid code adds a MEMBER and accepts formatting noise`() {
        val harry = createUser()
        val sarah = createUser()
        val household = harry.createHousehold()
        val code = createCode(harry, household)["code"] as String

        val result = join(sarah, " ${code.take(4).lowercase()}-${code.drop(4)} ")
        assertEquals("joined", result["status"])
        assertEquals(household.toString(), result["household"])
        assertEquals("MEMBER", roleOf(household, sarah))

        assertEquals("already_member", join(sarah, code)["status"])
    }

    @Test
    fun `invalid code reveals nothing`() {
        val sarah = createUser()
        val result = join(sarah, "ZZZZZZZZ")
        assertEquals("invalid", result["status"])
        assertNull(result["household"])
    }

    @Test
    fun `expired, revoked and used-up codes are rejected`() {
        val harry = createUser()
        val household = harry.createHousehold()

        val expired = createCode(harry, household)
        asAdmin { update("update public.household_invite_codes set expires_at = now() - interval '1 minute' where id = ?", expired["id"]) }
        assertEquals("expired", join(createUser(), expired["code"] as String)["status"])

        val revoked = createCode(harry, household)
        asUser(harry) { query("select public.revoke_invite_code(?)", revoked["id"]) }
        assertEquals("invalid", join(createUser(), revoked["code"] as String)["status"])

        val singleUse = createCode(harry, household, maxUses = 1)
        assertEquals("joined", join(createUser(), singleUse["code"] as String)["status"])
        assertEquals("expired", join(createUser(), singleUse["code"] as String)["status"])
    }

    @Test
    fun `repeated failures are rate limited, even for a valid code`() {
        val harry = createUser()
        val attacker = createUser()
        val household = harry.createHousehold()
        val code = createCode(harry, household)["code"] as String

        repeat(10) { assertEquals("invalid", join(attacker, "AAAAAAA${it + 2}")["status"]) }
        assertEquals("rate_limited", join(attacker, code)["status"])
        assertNull(roleOf(household, attacker))
    }

    @Test
    fun `only members with INVITE_MEMBERS can create or see codes`() {
        val harry = createUser()
        val child = createUser(isChild = true)
        val outsider = createUser()
        val household = harry.createHousehold()
        asAdmin { update("insert into public.household_members (household_id, user_id, role) values (?, ?, 'CHILD')", household, child.id) }
        createCode(harry, household)

        assertPermissionDenied { createCode(child, household) }
        assertPermissionDenied { createCode(outsider, household) }
        asUser(child) { assertEquals(0, count("select 1 from public.household_invite_codes where household_id = ?", household)) }
        asUser(harry) { assertEquals(1, count("select 1 from public.household_invite_codes where household_id = ?", household)) }
    }

    @Test
    fun `children cannot join households with codes`() {
        val harry = createUser()
        val child = createUser(isChild = true)
        val code = createCode(harry, harry.createHousehold())["code"] as String
        assertPermissionDenied { join(child, code) }
    }
}
