package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth

class MonthSummaryTest {
    private val october = YearMonth.of(2026, 10)
    private val parents = mapOf("coffee" to "food", "groceries" to "food", "transit" to "transport")

    private fun entry(cents: Long, categoryId: String?, day: Int = 10, kind: Kind = Kind.EXPENSE, month: Int = 10) =
        LedgerEntry(cents, kind, categoryId, LocalDate.of(2026, month, day))

    private fun summarize(vararg entries: LedgerEntry) = MonthSummary.of(october, entries.toList()) { parents[it] }

    @Test
    fun `totals expenses by top-level category with subcategory breakdown`() {
        val summary = summarize(
            entry(425, "coffee"),
            entry(300, "coffee"),
            entry(5000, "groceries"),
            entry(330, "transit"),
        )

        assertEquals(6055L, summary.spentCents)
        assertEquals(
            listOf(
                CategoryTotal("food", 5725, listOf(CategoryTotal("groceries", 5000), CategoryTotal("coffee", 725))),
                CategoryTotal("transport", 330, listOf(CategoryTotal("transit", 330))),
            ),
            summary.categories,
        )
    }

    @Test
    fun `spending on a parent directly counts toward it without a child row`() {
        val summary = summarize(entry(1000, "food"), entry(425, "coffee"))
        assertEquals(listOf(CategoryTotal("food", 1425, listOf(CategoryTotal("coffee", 425)))), summary.categories)
    }

    @Test
    fun `uncategorized spending gets a null bucket`() {
        val summary = summarize(entry(999, null))
        assertEquals(listOf(CategoryTotal(null, 999)), summary.categories)
    }

    @Test
    fun `refunds reduce spending, transfers are ignored, income is separate`() {
        val summary = summarize(
            entry(5000, "groceries"),
            entry(-1200, "groceries"),
            entry(80000, null, kind = Kind.TRANSFER),
            entry(-150000, null, kind = Kind.INCOME),
        )
        assertEquals(3800L, summary.spentCents)
        assertEquals(150000L, summary.incomeCents)
        assertEquals(listOf("food"), summary.categories.map { it.categoryId })
    }

    @Test
    fun `only counts the requested month`() {
        val summary = summarize(entry(100, "coffee", day = 30, month = 9), entry(200, "coffee", day = 1), entry(400, "coffee", day = 1, month = 11))
        assertEquals(200L, summary.spentCents)
    }
}
