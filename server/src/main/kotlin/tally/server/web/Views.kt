package tally.server.web

import tally.core.model.Kind
import tally.core.report.BudgetStatus
import tally.core.report.BudgetedCategory
import tally.core.report.Budgets
import tally.core.report.MonthSummary
import tally.core.report.MonthTotals
import tally.core.report.Trends
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the overview page shows for one month. Like the phone's Home, it's about everyday
 * spending: categories switched off (rent, bills) show only as "fixed" beside the number.
 */
class Overview(val snapshot: Snapshot, val month: YearMonth, val today: LocalDate) {
    /** All spending, with the everyday and fixed parts. */
    val summary = MonthSummary.of(month, snapshot.entries, snapshot.counting, snapshot::parentOf)

    private val everydayEntries = snapshot.entries.filter { it.kind != Kind.EXPENSE || snapshot.counting.counts(it) }

    /** Everyday spending only, by category: "Where it went". */
    val everyday = MonthSummary.of(month, everydayEntries, parentOf = snapshot::parentOf)
    val lastMonth = MonthSummary.of(month.minusMonths(1), everydayEntries, parentOf = snapshot::parentOf)

    private val budgeted = snapshot.categoryTree
        .filter { snapshot.counting.categoryCounts(it.id) }
        .map { BudgetedCategory(it.id, it.parentId, it.monthlyBudgetCents) }
    val budgetCents: Long? = Budgets.monthlyTotal(budgeted)

    /** A top-level category's budget: its own, or its subcategories' added up. */
    fun budgetOf(topLevelId: String?): Long? = topLevelId?.let { id ->
        Budgets.monthlyTotal(budgeted.filter { it.id == id || it.parentId == id })
    }

    fun status(spent: Long, budget: Long?): BudgetStatus? = budget?.let { BudgetStatus.of(spent, it) }

    /** Running spend by day of month; this month stops at [today]. */
    val cumulative: List<Long?> = running(month, until = if (YearMonth.from(today) == month) today.dayOfMonth else null)
    val cumulativeLast: List<Long?> = running(month.minusMonths(1), until = null)

    /** A year of months, oldest first; the chart shows the last 6 or all 12. */
    val months: List<MonthTotals> = Trends.months(everydayEntries, month, count = 12)

    /** The month's latest transactions, newest first. */
    val recent = snapshot.transactions.filter { YearMonth.from(snapshot.date(it)) == month }.take(8)
    val topMerchants = Trends.topMerchants(everydayEntries, month.atDay(1), month.atEndOfMonth(), limit = 8)

    private fun running(m: YearMonth, until: Int?): List<Long?> {
        val byDay = everydayEntries.filter { it.kind == Kind.EXPENSE && YearMonth.from(it.date) == m }
            .groupBy { it.date.dayOfMonth }.mapValues { (_, list) -> list.sumOf { it.amountCents } }
        var total = 0L
        return (1..m.lengthOfMonth()).map { day ->
            total += byDay[day] ?: 0
            if (until != null && day > until) null else total
        }
    }
}

/** Ledger filters, from the page's query string. Blank means "any". */
data class LedgerQuery(
    val from: LocalDate,
    val to: LocalDate,
    val categoryId: String? = null,
    val accountId: String? = null,
    val tagId: String? = null,
    val text: String? = null,
) {
    fun apply(s: Snapshot): List<TransactionRow> {
        val needle = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return s.transactions.filter { t ->
            val date = s.date(t)
            !date.isBefore(from) && !date.isAfter(to) &&
                (categoryId == null || t.categoryId == categoryId || (t.categoryId != null && s.parentOf(t.categoryId) == categoryId) ||
                    (categoryId == UNCATEGORIZED && t.categoryId == null)) &&
                (accountId == null || t.accountId == accountId) &&
                (tagId == null || s.tagsByTransaction[t.id].orEmpty().any { it.id == tagId }) &&
                (needle == null || listOfNotNull(s.merchantName(t), t.merchantRaw, t.note).any { needle in it.lowercase() })
        }
    }

    companion object {
        const val UNCATEGORIZED = "none"

        fun month(month: YearMonth) = LedgerQuery(month.atDay(1), month.atEndOfMonth())
    }
}
