package tally.core.rules

/**
 * "Merchants matching [merchantPattern] (and in this amount range, on this account) go to
 * [categoryId]." [merchantPattern] is a case-insensitive regex that must match the whole
 * normalized merchant name, so "Uber" doesn't catch "Uber Eats" (write "Uber.*" for that).
 *
 * A blank [merchantPattern] with an amount condition matches money out with **no** merchant:
 * a bare bank alert, like rent leaving chequing as "A withdrawal of $750.00".
 */
data class Rule(
    val id: String,
    /** Lower runs first. */
    val priority: Int,
    val merchantPattern: String,
    val minAmountCents: Long? = null,
    val maxAmountCents: Long? = null,
    val accountId: String? = null,
    val categoryId: String,
    val tags: List<String> = emptyList(),
    /** A default to confirm in the inbox, not an automatic filing. */
    val ask: Boolean = false,
) {
    /** Matches only transactions without a merchant (bank alerts), by amount. */
    val noMerchant: Boolean get() = merchantPattern.isBlank()

    companion object {
        /**
         * Returns why [pattern] is unusable, or null. Rules are checked when saved, never when
         * matched. A blank pattern is fine for a rule with an amount ([forRule] with min or max).
         */
        fun patternError(pattern: String, forRule: Boolean = false, minAmountCents: Long? = null, maxAmountCents: Long? = null): String? {
            if (pattern.isBlank()) {
                return when {
                    !forRule -> "Merchant pattern is empty"
                    minAmountCents == null && maxAmountCents == null -> NO_MERCHANT_NEEDS_AMOUNT
                    else -> null
                }
            }
            return try {
                Regex(pattern)
                null
            } catch (e: Exception) {
                "Invalid pattern: ${e.message?.lineSequence()?.firstOrNull() ?: pattern}"
            }
        }

        /** A pattern matching exactly [name], for rules created from a transaction. */
        fun literal(name: String): String = buildString {
            for (c in name) {
                if (c in REGEX_SPECIAL) append('\\')
                append(c)
            }
        }

        /**
         * The name a pattern matches if it's a plain name ([literal] output, or typed without
         * regex syntax), or null if it's a real regex ("Uber.*", "A|B").
         */
        fun literalName(pattern: String): String? = buildString {
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '\\' && i + 1 < pattern.length && pattern[i + 1] in REGEX_SPECIAL) {
                    append(pattern[i + 1])
                    i += 2
                    continue
                }
                if (c in REGEX_SPECIAL) return null
                append(c)
                i++
            }
        }

        /** Two spellings of one merchant ("Foodie Fruitie", "FOODIE-FRUITIE") share this key. */
        fun merchantKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

        const val NO_MERCHANT_NEEDS_AMOUNT = "With no merchant, add an amount (otherwise it would catch every bank alert)"

        private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"
    }
}

/**
 * Applies rules in priority order; the first match wins. Patterns are compiled once. A
 * transaction with a merchant meets only patterned rules; one without (a bank alert) meets
 * only no-merchant rules, and only when it's money out.
 */
class RulesEngine(rules: List<Rule>) {
    private val compiled: List<Pair<Rule, Regex?>> = rules
        .sortedBy { it.priority }
        .mapNotNull { rule ->
            when {
                // A no-merchant rule without an amount can't be saved; one in a hand-edited database is skipped.
                rule.noMerchant -> if (rule.minAmountCents == null && rule.maxAmountCents == null) null else rule to null
                // Invalid patterns can't be saved, but a hand-edited database shouldn't crash capture.
                else -> runCatching { rule to Regex(rule.merchantPattern, RegexOption.IGNORE_CASE) }.getOrNull()
            }
        }

    fun match(merchant: String?, amountCents: Long, accountId: String?): Rule? {
        val bare = merchant.isNullOrBlank()
        if (bare && amountCents <= 0) return null
        return compiled.firstOrNull { (rule, regex) ->
            (if (bare) regex == null else regex != null && regex.matches(merchant!!)) &&
                (rule.minAmountCents == null || amountCents >= rule.minAmountCents) &&
                (rule.maxAmountCents == null || amountCents <= rule.maxAmountCents) &&
                (rule.accountId == null || rule.accountId == accountId)
        }?.first
    }

    companion object {
        val EMPTY = RulesEngine(emptyList())
    }
}
