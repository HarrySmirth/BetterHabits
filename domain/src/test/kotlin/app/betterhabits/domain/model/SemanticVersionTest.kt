package app.betterhabits.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `parses plain, tag and suffixed versions`() {
        assertEquals(SemanticVersion(0, 1, 0), SemanticVersion.parse("0.1.0"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3-debug"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("latest"))
        assertNull(SemanticVersion.parse("1.2.x"))
    }

    @Test
    fun `compares numerically rather than lexically`() {
        assertTrue(SemanticVersion.parse("0.10.0")!! > SemanticVersion.parse("0.9.9")!!)
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("0.99.99")!!)
        assertTrue(SemanticVersion.parse("0.1.1")!! > SemanticVersion.parse("0.1.0")!!)
    }
}
