package tally.core.report

/** A category's place in the tree and its monthly budget, if any. */
data class BudgetedCategory(val id: String, val parentId: String?, val monthlyBudgetCents: Long?)

enum class BudgetStatus {
    /** Under 80%. */
    OK,

    /** 80% to 100%: slow down. */
    WARNING,

    /** Over budget. */
    OVER,
    ;

    companion object {
        const val WARNING_AT = 0.8

        fun of(spentCents: Long, budgetCents: Long): BudgetStatus = when {
            budgetCents <= 0 -> OVER
            spentCents > budgetCents -> OVER
            spentCents >= budgetCents * WARNING_AT -> WARNING
            else -> OK
        }
    }
}

object Budgets {
    /**
     * The total monthly budget: per top-level category, its own budget if set, otherwise the sum
     * of its subcategories' budgets (so a parent budget and child budgets never double count).
     * Null when nothing has a budget.
     */
    fun monthlyTotal(categories: List<BudgetedCategory>): Long? {
        val parts = categories.filter { it.parentId == null }.mapNotNull { parent ->
            parent.monthlyBudgetCents ?: categories
                .filter { it.parentId == parent.id }
                .mapNotNull { it.monthlyBudgetCents }
                .takeIf { it.isNotEmpty() }
                ?.sum()
        }
        return if (parts.isEmpty()) null else parts.sum()
    }
}
