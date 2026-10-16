package tally.core.report

import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth

/** A transaction as reports see it. [date] is the local date in the zone it happened in. */
data class LedgerEntry(
    /** Positive = money out, negative = money in (refunds, deposits). */
    val amountCents: Long,
    val kind: Kind,
    val categoryId: String?,
    val date: LocalDate,
)

/**
 * Spending in one category. For a top-level category, [cents] includes its subcategories,
 * and [children] lists the subcategories with spending (largest first). Transactions put
 * directly on the parent count toward [cents] but have no child row.
 */
data class CategoryTotal(
    /** Null for uncategorized spending. */
    val categoryId: String?,
    val cents: Long,
    val children: List<CategoryTotal> = emptyList(),
)

data class MonthSummary(
    val month: YearMonth,
    /** Net expenses: purchases minus refunds. Transfers never count. */
    val spentCents: Long,
    /** Money in from INCOME transactions, as a positive number. */
    val incomeCents: Long,
    /** Top-level categories, largest first. */
    val categories: List<CategoryTotal>,
) {
    companion object {
        /** [parentOf] maps a category id to its parent's id, or null for a top-level category. */
        fun of(month: YearMonth, entries: List<LedgerEntry>, parentOf: (String) -> String?): MonthSummary {
            val inMonth = entries.filter { YearMonth.from(it.date) == month }
            val expenses = inMonth.filter { it.kind == Kind.EXPENSE }
            val byTopLevel = expenses.groupBy { entry -> entry.categoryId?.let { parentOf(it) ?: it } }
            val categories = byTopLevel.map { (topLevel, list) ->
                val children = list.filter { it.categoryId != topLevel }
                    .groupBy { it.categoryId }
                    .map { (id, childList) -> CategoryTotal(id, childList.sumOf { it.amountCents }) }
                    .sortedByDescending { it.cents }
                CategoryTotal(topLevel, list.sumOf { it.amountCents }, children)
            }.sortedByDescending { it.cents }
            return MonthSummary(
                month = month,
                spentCents = expenses.sumOf { it.amountCents },
                incomeCents = -inMonth.filter { it.kind == Kind.INCOME }.sumOf { it.amountCents },
                categories = categories,
            )
        }
    }
}
