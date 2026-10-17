package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth

class SplitsTest {
    private val day = LocalDate.of(2026, 10, 16)
    private val dinner = LedgerEntry(3000, Kind.EXPENSE, "dining", day)

    @Test
    fun `no splits is the entry itself`() {
        assertEquals(listOf(dinner), dinner.withSplits(emptyList()))
    }

    @Test
    fun `categorized part plus remainder`() {
        assertEquals(
            listOf(LedgerEntry(1000, Kind.EXPENSE, "coffee", day), LedgerEntry(2000, Kind.EXPENSE, "dining", day)),
            dinner.withSplits(listOf(SplitPart(1000, "coffee", null))),
        )
    }

    @Test
    fun `owed parts are not spending`() {
        val entries = dinner.withSplits(listOf(SplitPart(1000, null, "Sam")))
        val summary = MonthSummary.of(YearMonth.of(2026, 10), entries) { null }
        assertEquals(2000L, summary.spentCents)
    }

    @Test
    fun `fully split leaves no remainder, and non-expenses are untouched`() {
        assertEquals(1, dinner.withSplits(listOf(SplitPart(3000, "coffee", null))).size)
        val income = LedgerEntry(-5000, Kind.INCOME, null, day)
        assertEquals(listOf(income), income.withSplits(listOf(SplitPart(1000, "coffee", null))))
    }
}
