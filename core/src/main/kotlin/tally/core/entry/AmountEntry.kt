package tally.core.entry

/**
 * Numpad entry where digits fill in from the right as cents, like a card terminal:
 * typing 4, 2, 5 gives 425 cents ($4.25). No decimal key, no floats.
 */
object AmountEntry {
    /** $99,999,999.99; further digits are ignored. */
    const val MAX_CENTS = 99_999_999_99L

    fun type(cents: Long, digit: Int): Long {
        require(digit in 0..9) { "not a digit: $digit" }
        val next = cents * 10 + digit
        return if (next > MAX_CENTS) cents else next
    }

    fun backspace(cents: Long): Long = cents / 10
}
