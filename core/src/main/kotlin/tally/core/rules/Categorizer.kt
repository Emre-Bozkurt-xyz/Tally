package tally.core.rules

import tally.core.model.CategorySource

enum class Confidence {
    /** Log silently as confirmed. */
    HIGH,

    /** Log with a category, but ask via the capture notification. */
    MEDIUM,

    /** No idea: notify without a category. */
    LOW,
}

/** What Tally remembers about a merchant: the category you last chose and how often in a row. */
data class MerchantMemory(val lastCategoryId: String?, val confirmCount: Int) {
    /** After you confirm [categoryId] for this merchant. A different category starts over. */
    fun confirmed(categoryId: String): MerchantMemory =
        if (categoryId == lastCategoryId) copy(confirmCount = confirmCount + 1) else MerchantMemory(categoryId, 1)

    companion object {
        /** Confirmations of the same category before captures are logged without asking. */
        const val TRUSTED_AFTER = 2
    }
}

data class Suggestion(
    val categoryId: String?,
    val source: CategorySource?,
    val confidence: Confidence,
    /** Tags from the matching rule. */
    val tags: List<String> = emptyList(),
)

/**
 * Picks a category for a capture: your rules first (you said so explicitly: trusted, unless
 * the rule says `ask`), then merchant memory, then nothing (the LLM fills in later).
 */
class Categorizer(private val rules: RulesEngine = RulesEngine.EMPTY) {
    fun categorize(
        merchant: String?,
        amountCents: Long,
        accountId: String?,
        memory: MerchantMemory?,
        /** False for money to and from people: the same person, a different reason each time. */
        trustMemory: Boolean = true,
    ): Suggestion {
        rules.match(merchant, amountCents, accountId)?.let { rule ->
            return Suggestion(rule.categoryId, CategorySource.RULE, if (rule.ask) Confidence.MEDIUM else Confidence.HIGH, rule.tags)
        }
        val categoryId = memory?.lastCategoryId
            ?: return Suggestion(categoryId = null, source = null, confidence = Confidence.LOW)
        val confidence = if (trustMemory && memory.confirmCount >= MerchantMemory.TRUSTED_AFTER) Confidence.HIGH else Confidence.MEDIUM
        return Suggestion(categoryId, CategorySource.MEMORY, confidence)
    }
}
