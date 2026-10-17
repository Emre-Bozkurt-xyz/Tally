package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth

class BudgetsAndTrendsTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun spend(cents: Long, date: LocalDate, merchant: String? = null) =
        LedgerEntry(cents, Kind.EXPENSE, null, date, merchant)

    @Test
    fun `budget status thresholds`() {
        assertEquals(BudgetStatus.OK, BudgetStatus.of(7999, 10000))
        assertEquals(BudgetStatus.WARNING, BudgetStatus.of(8000, 10000))
        assertEquals(BudgetStatus.WARNING, BudgetStatus.of(10000, 10000))
        assertEquals(BudgetStatus.OVER, BudgetStatus.of(10001, 10000))
    }

    @Test
    fun `monthly total uses parent budgets, else children, never both`() {
        val categories = listOf(
            BudgetedCategory("food", null, 40000),
            BudgetedCategory("coffee", "food", 5000), // Ignored: Food has its own budget.
            BudgetedCategory("transport", null, null),
            BudgetedCategory("transit", "transport", 12000),
            BudgetedCategory("rideshare", "transport", 3000),
            BudgetedCategory("health", null, null),
        )
        assertEquals(40000L + 15000L, Budgets.monthlyTotal(categories))
        assertNull(Budgets.monthlyTotal(listOf(BudgetedCategory("x", null, null))))
    }

    @Test
    fun `month trend fills gaps and nets income`() {
        val entries = listOf(
            spend(1000, LocalDate.of(2026, 8, 3)),
            spend(2500, LocalDate.of(2026, 10, 1)),
            LedgerEntry(-150000, Kind.INCOME, null, LocalDate.of(2026, 10, 2)),
            LedgerEntry(80000, Kind.TRANSFER, null, LocalDate.of(2026, 10, 2)),
        )
        val months = Trends.months(entries, YearMonth.of(2026, 10), count = 3)
        assertEquals(
            listOf(
                MonthTotals(YearMonth.of(2026, 8), 1000, 0),
                MonthTotals(YearMonth.of(2026, 9), 0, 0),
                MonthTotals(YearMonth.of(2026, 10), 2500, 150000),
            ),
            months,
        )
        assertEquals(147500L, months.last().netCents)
    }

    @Test
    fun `top merchants in range`() {
        val entries = listOf(
            spend(500, today, "tims"),
            spend(450, today.minusDays(1), "tims"),
            spend(3432, today.minusDays(2), "foodie"),
            spend(9999, today.minusDays(40), "old"),
        )
        assertEquals(
            listOf(MerchantTotal("foodie", 3432, 1), MerchantTotal("tims", 950, 2)),
            Trends.topMerchants(entries, today.minusDays(30), today),
        )
    }

    @Test
    fun `monthly subscription with a small price change`() {
        val entries = listOf(
            spend(1699, LocalDate.of(2026, 7, 14), "netflix"),
            spend(1699, LocalDate.of(2026, 8, 14), "netflix"),
            spend(1799, LocalDate.of(2026, 9, 13), "netflix"),
            spend(1799, LocalDate.of(2026, 10, 14), "netflix"),
        )
        val found = Trends.recurring(entries, today).single()
        assertEquals(Recurring.Period.MONTHLY, found.period)
        assertEquals(1799L, found.amountCents)
        assertEquals(1799L * 12, found.yearlyCostCents)
        assertEquals(LocalDate.of(2026, 11, 13), found.nextExpected)
    }

    @Test
    fun `yearly subscription, and things that are not subscriptions`() {
        val yearly = listOf(spend(13900, LocalDate.of(2025, 10, 20), "amazon-prime"), spend(13900, LocalDate.of(2026, 10, 20), "amazon-prime"))
        assertEquals(Recurring.Period.YEARLY, Trends.recurring(yearly, LocalDate.of(2026, 10, 21)).single().period)

        val groceries = (0..5).map { spend(5000L + it * 1700, LocalDate.of(2026, 5 + it, 3), "metro") } // Amounts vary a lot.
        val coffee = (0..20).map { spend(425, today.minusDays(it.toLong()), "tims") } // Daily, not monthly.
        val stale = listOf(spend(999, LocalDate.of(2026, 1, 1), "gym"), spend(999, LocalDate.of(2026, 2, 1), "gym"))
        val once = listOf(spend(999, today, "once"))
        assertEquals(emptyList<Recurring>(), Trends.recurring(groceries + coffee + stale + once, today))
    }
}
