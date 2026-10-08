package app.betterhabits.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InviteCodesAndValidationTest {

    @Test
    fun `normalizes what people type`() {
        assertEquals("H7K4P9QX", InviteCodes.normalize(" h7k4-p9qx "))
        assertTrue(InviteCodes.isWellFormed("H7K4P9QX"))
    }

    @Test
    fun `rejects wrong length and ambiguous characters`() {
        assertFalse(InviteCodes.isWellFormed("H7K4P9Q"))
        assertFalse(InviteCodes.isWellFormed("H7K4P9Q0")) // zero is not in the alphabet
        assertFalse(InviteCodes.isWellFormed("H7K4P9QI"))
    }

    @Test
    fun `formats codes in two groups`() {
        assertEquals("H7K4-P9QX", InviteCodes.format("H7K4P9QX"))
    }

    @Test
    fun `invite code activity`() {
        val now = Instant.parse("2026-10-08T12:00:00Z")
        val code = InviteCode("1", "H7K4P9QX", now.plusSeconds(60), maxUses = 2, useCount = 1, revoked = false)
        assertTrue(code.isActive(now))
        assertFalse(code.copy(useCount = 2).isActive(now))
        assertFalse(code.copy(revoked = true).isActive(now))
        assertFalse(code.isActive(now.plusSeconds(60)))
    }

    @Test
    fun `form validation`() {
        assertTrue(Validation.isValidEmail("sarah@example.com"))
        assertFalse(Validation.isValidEmail("sarah@example"))
        assertFalse(Validation.isValidPassword("short"))
        assertTrue(Validation.isValidPassword("long enough"))
        assertTrue(Validation.isValidChildPin("123456"))
        assertFalse(Validation.isValidChildPin("12345"))
        assertFalse(Validation.isValidDisplayName("   "))
        assertTrue(Validation.isValidOtp("042917"))
        assertFalse(Validation.isValidOtp("42917a"))
    }

    @Test
    fun `child usernames map to the child email domain`() {
        assertEquals("alex-7k2p@kids.invalid", ChildLogin.emailFor(" Alex-7K2P ", "kids.invalid"))
        assertTrue(ChildLogin.isChildEmail("alex-7k2p@kids.invalid", "kids.invalid"))
        assertEquals("alex-7k2p", ChildLogin.usernameFrom("alex-7k2p@kids.invalid", "kids.invalid"))
    }
}
