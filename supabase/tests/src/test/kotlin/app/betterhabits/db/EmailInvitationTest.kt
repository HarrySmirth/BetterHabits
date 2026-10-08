package app.betterhabits.db

import app.betterhabits.db.TestDatabase.asAdmin
import app.betterhabits.db.TestDatabase.asUser
import app.betterhabits.db.TestDatabase.createUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class EmailInvitationTest {

    private fun invite(by: TestUser, household: UUID, email: String, role: String = "MEMBER"): UUID =
        asUser(by) { scalar("select id from public.create_invitation(?, ?, ?::public.household_role)", household, email, role) as UUID }

    @Test
    fun `invitee sees and accepts an invitation addressed to their email`() {
        val harry = createUser(name = "Harry")
        val sarah = createUser(email = "sarah-${UUID.randomUUID()}@example.test")
        val household = harry.createHousehold("Harry & Sarah")
        val invitation = invite(harry, household, sarah.email.uppercase())

        val pending = asUser(sarah) { query("select * from public.my_pending_invitations()") }.single()
        assertEquals("Harry & Sarah", pending["household_name"])
        assertEquals("Harry", pending["invited_by_name"])

        asUser(sarah) { query("select public.respond_to_invitation(?, true)", invitation) }
        assertEquals("MEMBER", roleOf(household, sarah))
        asUser(sarah) { assertEquals(0, count("select * from public.my_pending_invitations()")) }
    }

    @Test
    fun `other users cannot see or accept someone else's invitation`() {
        val harry = createUser()
        val eve = createUser()
        val household = harry.createHousehold()
        val invitation = invite(harry, household, "sarah-${UUID.randomUUID()}@example.test")

        asUser(eve) { assertEquals(0, count("select * from public.my_pending_invitations()")) }
        assertDbError("invitation_not_found") { asUser(eve) { query("select public.respond_to_invitation(?, true)", invitation) } }
        assertNull(roleOf(household, eve))
    }

    @Test
    fun `declined, revoked and expired invitations cannot be accepted`() {
        val harry = createUser()
        val sarah = createUser()
        val household = harry.createHousehold()

        val declined = invite(harry, household, sarah.email)
        asUser(sarah) { query("select public.respond_to_invitation(?, false)", declined) }
        assertNull(roleOf(household, sarah))

        val revoked = invite(harry, household, sarah.email)
        asUser(harry) { query("select public.revoke_invitation(?)", revoked) }
        assertDbError("invitation_not_found") { asUser(sarah) { query("select public.respond_to_invitation(?, true)", revoked) } }

        val expired = invite(harry, household, sarah.email)
        asAdmin { update("update public.household_invitations set expires_at = now() - interval '1 second' where id = ?", expired) }
        assertDbError("invitation_expired") { asUser(sarah) { query("select public.respond_to_invitation(?, true)", expired) } }
    }

    @Test
    fun `re-inviting refreshes the pending invitation instead of duplicating it`() {
        val harry = createUser()
        val household = harry.createHousehold()
        val email = "dup-${UUID.randomUUID()}@example.test"
        assertEquals(invite(harry, household, email), invite(harry, household, email))
    }

    @Test
    fun `only owners can invite admins and existing members cannot be invited`() {
        val harry = createUser()
        val sarah = createUser()
        val household = harry.createHousehold()
        addMemberWithCode(household, harry, sarah)

        assertPermissionDenied { invite(sarah, household, "x-${UUID.randomUUID()}@example.test", role = "ADMIN") }
        invite(harry, household, "admin-${UUID.randomUUID()}@example.test", role = "ADMIN")
        assertDbError("already_member") { invite(harry, household, sarah.email) }
    }
}
