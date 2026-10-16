package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.DayOfWeek
import java.time.LocalDate

class WeekSpendTest {
    // Friday.
    private val today = LocalDate.of(2026, 10, 16)

    private fun entry(cents: Long, date: LocalDate, kind: Kind = Kind.EXPENSE) = LedgerEntry(cents, kind, null, date)

    @Test
    fun `sums expenses from the start of the week through today`() {
        val week = WeekSpend.of(
            entries = listOf(
                entry(425, LocalDate.of(2026, 10, 12)), // Monday
                entry(1000, today),
                entry(-200, today), // refund
                entry(5000, today, Kind.TRANSFER),
                entry(-90000, today, Kind.INCOME),
                entry(777, LocalDate.of(2026, 10, 11)), // Sunday before
            ),
            today = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            monthlyBudgetsCents = emptyList(),
        )
        assertEquals(LocalDate.of(2026, 10, 12), week.start)
        assertEquals(1225L, week.spentCents)
        assertNull(week.budgetCents)
    }

    @Test
    fun `week can start on Sunday`() {
        val week = WeekSpend.of(
            entries = listOf(entry(777, LocalDate.of(2026, 10, 11))),
            today = today,
            firstDayOfWeek = DayOfWeek.SUNDAY,
            monthlyBudgetsCents = emptyList(),
        )
        assertEquals(LocalDate.of(2026, 10, 11), week.start)
        assertEquals(777L, week.spentCents)
    }

    @Test
    fun `weekly budget is monthly budgets times 12 over 52`() {
        assertEquals(12000L, WeekSpend.weeklyBudget(listOf(40000, 12000)))
        assertNull(WeekSpend.weeklyBudget(emptyList()))
    }
}
