package tally.core.rules

/** A transaction as rule replay sees it. */
data class ReplayCandidate(
    val id: String,
    /** The cleaned-up merchant name rules match against. */
    val merchant: String?,
    val amountCents: Long,
    val accountId: String?,
    val categoryId: String?,
    /** Still waiting in the inbox. */
    val captured: Boolean,
    /** Between your own accounts: never categorized. */
    val transfer: Boolean,
)

/** [categoryId] from [ruleId]: filed ([confirm]) or suggested in the inbox (an `ask` rule). */
data class ReplayChange(val id: String, val categoryId: String, val confirm: Boolean, val ruleId: String)

/**
 * Runs your rules over transactions that already exist, so a rule written after a statement
 * import also files what's sitting in the inbox. Only touches transactions still waiting
 * (in the inbox) or without a category, never ones you've filed, and never transfers.
 */
object RuleReplay {
    fun plan(candidates: List<ReplayCandidate>, rules: RulesEngine): List<ReplayChange> = candidates.mapNotNull { c ->
        if (c.transfer || (!c.captured && c.categoryId != null)) return@mapNotNull null
        val rule = rules.match(c.merchant, c.amountCents, c.accountId) ?: return@mapNotNull null
        // An ask rule only suggests: nothing to do if it's already the suggestion.
        if (rule.ask && rule.categoryId == c.categoryId) return@mapNotNull null
        ReplayChange(c.id, rule.categoryId, confirm = !rule.ask, ruleId = rule.id)
    }
}
