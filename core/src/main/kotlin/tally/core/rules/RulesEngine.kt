package tally.core.rules

/**
 * "Merchants matching [merchantPattern] (and in this amount range, on this account) go to
 * [categoryId]." [merchantPattern] is a case-insensitive regex that must match the whole
 * normalized merchant name, so "Uber" doesn't catch "Uber Eats" (write "Uber.*" for that).
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
) {
    companion object {
        /** Returns why [pattern] is unusable, or null. Rules are checked when saved, never when matched. */
        fun patternError(pattern: String): String? {
            if (pattern.isBlank()) return "Merchant pattern is empty"
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

        private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"
    }
}

/** Applies rules in priority order; the first match wins. Patterns are compiled once. */
class RulesEngine(rules: List<Rule>) {
    private val compiled: List<Pair<Rule, Regex>> = rules
        .sortedBy { it.priority }
        .mapNotNull { rule ->
            // Invalid patterns can't be saved, but a hand-edited database shouldn't crash capture.
            runCatching { rule to Regex(rule.merchantPattern, RegexOption.IGNORE_CASE) }.getOrNull()
        }

    fun match(merchant: String?, amountCents: Long, accountId: String?): Rule? {
        if (merchant == null) return null
        return compiled.firstOrNull { (rule, regex) ->
            regex.matches(merchant) &&
                (rule.minAmountCents == null || amountCents >= rule.minAmountCents) &&
                (rule.maxAmountCents == null || amountCents <= rule.maxAmountCents) &&
                (rule.accountId == null || rule.accountId == accountId)
        }?.first
    }

    companion object {
        val EMPTY = RulesEngine(emptyList())
    }
}
