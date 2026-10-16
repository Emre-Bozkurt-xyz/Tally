package tally.core.report

import tally.core.model.Kind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** This week's net spending, for the widget. */
data class WeekSpend(
    val start: LocalDate,
    val spentCents: Long,
    /** Derived from monthly budgets; null when none are set. */
    val budgetCents: Long?,
) {
    companion object {
        fun of(
            entries: List<LedgerEntry>,
            today: LocalDate,
            firstDayOfWeek: DayOfWeek,
            monthlyBudgetsCents: List<Long>,
        ): WeekSpend {
            val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val end = start.plusDays(6)
            val spent = entries
                .filter { it.kind == Kind.EXPENSE && it.date in start..end }
                .sumOf { it.amountCents }
            return WeekSpend(start, spent, weeklyBudget(monthlyBudgetsCents))
        }

        /** 12 months over 52 weeks. Null when no budgets are set. */
        fun weeklyBudget(monthlyBudgetsCents: List<Long>): Long? =
            if (monthlyBudgetsCents.isEmpty()) null else monthlyBudgetsCents.sum() * 12 / 52
    }
}
