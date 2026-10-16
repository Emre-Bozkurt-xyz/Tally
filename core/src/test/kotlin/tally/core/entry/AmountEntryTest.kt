package tally.core.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AmountEntryTest {
    private fun typeAll(digits: String, start: Long = 0): Long =
        digits.fold(start) { cents, c -> AmountEntry.type(cents, c.digitToInt()) }

    @Test
    fun `digits fill in from the right as cents`() {
        assertEquals(4L, typeAll("4"))
        assertEquals(42L, typeAll("42"))
        assertEquals(425L, typeAll("425"))
        assertEquals(1_234_56L, typeAll("123456"))
    }

    @Test
    fun `leading zeros do nothing`() {
        assertEquals(425L, typeAll("00425"))
    }

    @Test
    fun `typing past the maximum is ignored`() {
        val max = typeAll("9999999999")
        assertEquals(AmountEntry.MAX_CENTS, max)
        assertEquals(max, AmountEntry.type(max, 9))
    }

    @Test
    fun `backspace drops the last digit`() {
        assertEquals(42L, AmountEntry.backspace(425))
        assertEquals(0L, AmountEntry.backspace(4))
        assertEquals(0L, AmountEntry.backspace(0))
    }

    @Test
    fun `rejects non-digits`() {
        assertThrows<IllegalArgumentException> { AmountEntry.type(0, 10) }
    }
}
