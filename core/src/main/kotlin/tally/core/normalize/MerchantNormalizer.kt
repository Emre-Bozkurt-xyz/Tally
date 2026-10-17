package tally.core.normalize

/** A user rule: raw merchant strings matching [pattern] become [name]. */
data class Alias(val pattern: Regex, val name: String)

/**
 * Turns raw merchant strings ("SQ *TIM HORTONS #1234 HAMILTON ON") into display names
 * ("Tim Hortons"). Aliases win (first match); otherwise a generic cleanup runs.
 */
class MerchantNormalizer(private val aliases: List<Alias> = emptyList()) {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        aliases.firstOrNull { it.pattern.containsMatchIn(trimmed) }?.let { return it.name }

        var s = trimmed
        s = PROCESSOR_PREFIX.replace(s, "")
        s = CITY_PROVINCE_SUFFIX.replace(s, "")
        s = STORE_NUMBER.replace(s, " ")
        s = TRAILING_NUMBER.replace(s, "")
        s = WHITESPACE.replace(s, " ").trim().trimEnd('-', ',', '.', '*', ' ')
        if (s.isEmpty()) return trimmed
        return if (s.none { it.isLowerCase() } || s.none { it.isUpperCase() }) titleCase(s) else s
    }

    /** Capitalizes the first letter after the start, a space, or a hyphen ("7-Eleven"). */
    private fun titleCase(s: String): String =
        WORD_START.replace(s.lowercase()) { it.value.uppercase() }

    private companion object {
        /** Card processors that prefix the real merchant: "SQ *", "TST* ", "PAYPAL *". */
        val PROCESSOR_PREFIX = Regex("(?i)^(?:SQ|TST|SP|PAYPAL|PP|IC|DD|SUMUP|ZTL)\\s*\\*\\s*")

        /** Trailing "CITY ON" (one-word city, Canadian province code). */
        val CITY_PROVINCE_SUFFIX = Regex("\\s+[A-Z][A-Za-z.'-]*\\s+(?:ON|QC|BC|AB|MB|SK|NS|NB|NL|PE|YT|NT|NU)$")

        /** "#1234" anywhere. */
        val STORE_NUMBER = Regex("\\s*#\\s*\\d+")

        /** A trailing store number like " 01234" (3+ digits). */
        val TRAILING_NUMBER = Regex("\\s+\\d{3,}$")

        val WHITESPACE = Regex("\\s+")

        val WORD_START = Regex("(?<=^|[\\s-])\\p{L}")
    }
}
