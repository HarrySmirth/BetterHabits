package app.betterhabits.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.sql.SQLException
import java.util.UUID

/** Asserts [block] fails with a database error whose SQLSTATE or message key matches. */
fun assertDbError(expected: String, block: () -> Unit) {
    try {
        block()
    } catch (e: SQLException) {
        val matches = e.sqlState == expected || (e.message ?: "").contains(expected)
        assertTrue("Expected '$expected' but got [${e.sqlState}] ${e.message}", matches)
        return
    }
    fail("Expected database error '$expected' but the operation succeeded")
}

fun assertPermissionDenied(block: () -> Unit) = assertDbError("42501", block)

/** Creates a household owned by [owner] and returns its id. */
fun TestUser.createHousehold(name: String = "Home"): UUID =
    TestDatabase.asUser(this) { scalar("select public.create_household(?)", name) as UUID }

/** Adds [member] to [household] via a fresh invite code created by [inviter]. */
fun addMemberWithCode(household: UUID, inviter: TestUser, member: TestUser) {
    val code = TestDatabase.asUser(inviter) {
        scalar("select code from public.create_invite_code(?)", household) as String
    }
    val status = TestDatabase.asUser(member) {
        scalar("select public.join_household_with_code(?) ->> 'status'", code)
    }
    assertEquals("joined", status)
}

fun roleOf(household: UUID, user: TestUser): String? = TestDatabase.asAdmin {
    scalar("select role::text from public.household_members where household_id = ? and user_id = ?", household, user.id) as String?
}
