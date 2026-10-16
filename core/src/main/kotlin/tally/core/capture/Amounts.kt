package tally.core.capture

/**
 * Parses money amounts as they appear in bank and wallet notifications into cents.
 *
 * Handles `$1,234.56`, `CAD 4.25`, `C$4.25`, `-$5.00`, `($5.00)`, `$5`, and French Canadian
 * formats like `4,25 $` and `1 234,56 $` (space, no-break space or narrow no-break space as
 * the thousands separator).
 */
object Amounts {
    private val SPACES = Regex("[\\s\\u00A0\\u202F]")
    private val CURRENCY = Regex("(?i)CAD|CA\\$|C\\$|\\$")
    private val NUMBER = Regex("\\d[\\d.,]*")

    // Prefix form ($1,234.56, CAD 4.25): comma grouping only.
    private const val PREFIX_NUM = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"

    // Suffix form (1 234,56 $): French grouping by any kind of space, or by dots.
    private const val SUFFIX_NUM =
        "\\d{1,3}(?:[ \\u00A0\\u202F.]\\d{3})+(?:,\\d{1,2})?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"

    private val PREFIX = Regex("(?i)(?<![\\w.,])(-\\s*)?(?:CAD|CA\\$|C\\$|\\$)\\s*($PREFIX_NUM)(?![\\d])")
    // The trailing lookahead stops "ending 1234 $5.00" from reading as "1234 $".
    private val SUFFIX =
        Regex("(?i)(?<![\\w.,])(-\\s*)?($SUFFIX_NUM)[ \\u00A0\\u202F]?(?:\\$|CAD\\b)(?!\\s*\\d)")

    /**
     * Parses a single amount string. Returns null if it doesn't look like exactly one amount.
     * Sign comes from a leading minus or surrounding parentheses.
     */
    fun parseCents(raw: String): Long? {
        var s = raw.trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        s = s.replace('−', '-').replace(SPACES, "")
        if (s.startsWith("-")) {
            negative = !negative
            s = s.substring(1)
        }
        s = s.replace(CURRENCY, "")
        if (s.startsWith("-")) {
            negative = !negative
            s = s.substring(1)
        }
        if (!NUMBER.matches(s)) return null
        val cents = digitsToCents(s) ?: return null
        return if (negative) -cents else cents
    }

    /**
     * Finds the first amount in free text. Requires a currency marker (`$` or `CAD`) so card
     * digits like "ending in 1234" never match.
     */
    fun findFirst(text: String): Long? {
        val candidates = listOfNotNull(PREFIX.find(text), SUFFIX.find(text))
        val match = candidates.minByOrNull { it.range.first } ?: return null
        val cents = digitsToCents(match.groupValues[2].replace(SPACES, "")) ?: return null
        return if (match.groupValues[1].isNotEmpty()) -cents else cents
    }

    /** `s` contains only digits, `.` and `,`. The last separator is decimal iff 1-2 digits follow it. */
    private fun digitsToCents(s: String): Long? {
        val lastSep = s.indexOfLast { it == '.' || it == ',' }
        val (whole, fraction) = if (lastSep >= 0 && s.length - lastSep - 1 in 1..2) {
            s.substring(0, lastSep) to s.substring(lastSep + 1).padEnd(2, '0')
        } else {
            s to "00"
        }
        val wholeDigits = whole.filter { it.isDigit() }
        if (wholeDigits.isEmpty() || !validGrouping(whole)) return null
        val dollars = wholeDigits.toLongOrNull() ?: return null
        return dollars * 100 + fraction.toLong()
    }

    /** Separators in the whole part must split it into groups of exactly three after the first. */
    private fun validGrouping(whole: String): Boolean {
        val groups = whole.split('.', ',')
        if (groups.size == 1) return true
        return groups.first().length in 1..3 && groups.drop(1).all { it.length == 3 }
    }
}
