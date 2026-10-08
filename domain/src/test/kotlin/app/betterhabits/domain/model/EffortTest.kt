package app.betterhabits.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortTest {

    @Test
    fun `splits into hours and minutes`() {
        val effort = Effort(190)
        assertEquals(3, effort.hoursPart)
        assertEquals(10, effort.minutesPart)
    }

    @Test
    fun `sums a list of efforts`() {
        val total = listOf(Effort(5), Effort(45), Effort(10)).sum()
        assertEquals(Effort(60), total)
    }

    @Test
    fun `empty sum is zero`() {
        assertEquals(Effort.ZERO, emptyList<Effort>().sum())
    }

    @Test
    fun `orders by minutes`() {
        assertTrue(Effort(40) < Effort(45))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative effort`() {
        Effort(-1)
    }
}
