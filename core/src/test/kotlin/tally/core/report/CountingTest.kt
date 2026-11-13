package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CountingTest {
    private val parents = mapOf("coffee" to "food", "phone" to "bills", "rent" to "housing")

    // Bills is off (so Phone is too); Rent is off under a Housing that's on.
    private val counting = Counting(setOf("bills", "rent")) { parents[it] }

    private fun entry(cents: Long, categoryId: String?, counted: Boolean? = null, kind: Kind = Kind.EXPENSE, day: Int = 3) =
        LedgerEntry(cents, kind, categoryId, LocalDate.of(2026, 11, day), counted = counted)

    @Test
    fun `a category switched off takes its subcategories with it`() {
        assertTrue(counting.categoryCounts("coffee"))
        assertTrue(counting.categoryCounts(null))
        assertFalse(counting.categoryCounts("bills"))
        assertFalse(counting.categoryCounts("phone"))
        assertFalse(counting.categoryCounts("rent"))
        assertTrue(counting.categoryCounts("housing"))
    }

    @Test
    fun `a transaction's own setting wins both ways`() {
        assertTrue(counting.counts(entry(4_99, "coffee")))
        assertFalse(counting.counts(entry(1_200_00, "coffee", counted = false))) // An espresso machine.
        assertTrue(counting.counts(entry(55_00, "phone", counted = true)))
        assertFalse(counting.counts(entry(750_00, "rent")))
        assertFalse(counting.counts(entry(200_00, null, kind = Kind.TRANSFER, counted = true)))
        assertFalse(counting.counts(entry(-70_00, null, kind = Kind.INCOME)))
    }

    @Test
    fun `the month splits into everyday and fixed`() {
        val summary = MonthSummary.of(
            YearMonth.of(2026, 11),
            listOf(
                entry(750_00, "rent"),
                entry(55_00, "phone"),
                entry(12_40, "coffee"),
                entry(-2_40, "coffee"), // Refund.
                entry(30_00, null),
                entry(900_00, "food", counted = false),
                entry(200_00, null, kind = Kind.TRANSFER),
            ),
            counting,
        ) { parents[it] }
        assertEquals(1_745_00L, summary.spentCents)
        assertEquals(40_00L, summary.everydayCents)
        assertEquals(1_705_00L, summary.fixedCents)
        // Without switches, everything counts.
        assertEquals(750_00L, MonthSummary.of(YearMonth.of(2026, 11), listOf(entry(750_00, "rent"))) { parents[it] }.everydayCents)
    }

    @Test
    fun `split parts follow their own category, and the transaction's setting`() {
        val dinner = entry(100_00, "coffee").withSplits(listOf(SplitPart(30_00, "phone", null), SplitPart(50_00, null, "Sam")))
        assertEquals(20_00L, dinner.filter { counting.counts(it) }.sumOf { it.amountCents })
        val off = entry(100_00, "coffee", counted = false).withSplits(listOf(SplitPart(30_00, "phone", null)))
        assertTrue(off.none { counting.counts(it) })
    }

    @Test
    fun `the week on the widget leaves rent out`() {
        val week = WeekSpend.of(
            entries = listOf(entry(750_00, "rent", day = 3), entry(12_40, "coffee", day = 4), entry(55_00, "phone", day = 5)),
            today = LocalDate.of(2026, 11, 5),
            firstDayOfWeek = DayOfWeek.MONDAY,
            monthlyBudgetsCents = emptyList(),
            counting = counting,
        )
        assertEquals(12_40L, week.spentCents)
        assertEquals(817_40L, week.allCents)
    }
}
