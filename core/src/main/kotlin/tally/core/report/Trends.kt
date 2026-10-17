package tally.core.report

import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** One month's money in and out. */
data class MonthTotals(val month: YearMonth, val spentCents: Long, val incomeCents: Long) {
    val netCents get() = incomeCents - spentCents
}

data class MerchantTotal(val merchantId: String, val cents: Long, val count: Int)

/** A merchant you pay about every month (or year): probably a subscription. */
data class Recurring(
    val merchantId: String,
    val period: Period,
    /** The latest charge. */
    val amountCents: Long,
    val lastDate: LocalDate,
    val nextExpected: LocalDate,
    val count: Int,
) {
    enum class Period(val days: Long, val perYear: Int) { MONTHLY(30, 12), YEARLY(365, 1) }

    val yearlyCostCents get() = amountCents * period.perYear
}

object Trends {
    /** The [count] months ending with [last], oldest first; months without activity are zero. */
    fun months(entries: List<LedgerEntry>, last: YearMonth, count: Int = 6): List<MonthTotals> =
        (count - 1 downTo 0).map { back ->
            val month = last.minusMonths(back.toLong())
            val inMonth = entries.filter { YearMonth.from(it.date) == month }
            MonthTotals(
                month = month,
                spentCents = inMonth.filter { it.kind == Kind.EXPENSE }.sumOf { it.amountCents },
                incomeCents = -inMonth.filter { it.kind == Kind.INCOME }.sumOf { it.amountCents },
            )
        }

    /** Where the money went between [from] and [to] (inclusive), biggest first. */
    fun topMerchants(entries: List<LedgerEntry>, from: LocalDate, to: LocalDate, limit: Int = 10): List<MerchantTotal> =
        entries
            .filter { it.kind == Kind.EXPENSE && it.merchantId != null && it.date in from..to }
            .groupBy { it.merchantId!! }
            .map { (id, list) -> MerchantTotal(id, list.sumOf { it.amountCents }, list.size) }
            .filter { it.cents > 0 }
            .sortedByDescending { it.cents }
            .take(limit)

    /**
     * Finds subscriptions: a merchant charged at least twice, every gap close to a month
     * (25 to 35 days) or a year (350 to 380 days), each amount within 10% of the median.
     * Stale ones (more than two periods since the last charge) are dropped.
     */
    fun recurring(entries: List<LedgerEntry>, today: LocalDate): List<Recurring> =
        entries
            .filter { it.kind == Kind.EXPENSE && it.merchantId != null && it.amountCents > 0 }
            .groupBy { it.merchantId!! }
            .mapNotNull { (merchantId, charges) -> recurringFor(merchantId, charges.sortedBy { it.date }, today) }
            .sortedByDescending { it.yearlyCostCents }

    private fun recurringFor(merchantId: String, charges: List<LedgerEntry>, today: LocalDate): Recurring? {
        if (charges.size < 2) return null
        val gaps = charges.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        val period = when {
            gaps.all { it in 25..35 } -> Recurring.Period.MONTHLY
            gaps.all { it in 350..380 } -> Recurring.Period.YEARLY
            else -> return null
        }
        val median = charges.map { it.amountCents }.sorted()[charges.size / 2]
        if (charges.any { abs(it.amountCents - median) > (median * 0.1).roundToLong() }) return null
        val last = charges.last()
        if (ChronoUnit.DAYS.between(last.date, today) > period.days * 2) return null
        return Recurring(merchantId, period, last.amountCents, last.date, last.date.plusDays(period.days), charges.size)
    }
}
