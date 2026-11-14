package tally.app.ui.ledger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tally.app.data.CategoryEntity
import tally.app.data.LedgerRepository
import tally.app.data.SettlementEntity
import tally.app.data.SplitEntity
import tally.app.data.TransactionEntity
import tally.app.data.balancesOf
import tally.core.report.Balances
import tally.core.model.Kind
import tally.core.model.Source
import tally.core.report.Counting
import tally.core.report.LedgerEntry
import tally.core.report.SplitPart
import tally.core.report.withSplits
import tally.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A transaction as reports count it, splits expanded (owed parts drop out of spending). */
fun TransactionEntity.ledgerEntries(splits: List<SplitEntity>): List<LedgerEntry> =
    LedgerEntry(amountCents, Kind.valueOf(kind), categoryId, occurredAtLocal().toLocalDate(), merchantId, counted)
        .withSplits(splits.map { SplitPart(it.amountCents, it.categoryId, it.owedBy) })

/**
 * Everything reports count: each live transaction with its splits expanded, plus what calling
 * it even with someone absorbed (a shortfall is your spending, an overpayment money back).
 */
fun LedgerRepository.observeLedgerEntries(): Flow<List<LedgerEntry>> =
    combine(observeTransactions(), observeAllSplits(), observeSettlements()) { transactions, splits, settlements ->
        ledgerEntriesOf(transactions, splits, settlements)
    }

fun ledgerEntriesOf(
    transactions: List<TransactionEntity>,
    splits: List<SplitEntity>,
    settlements: List<SettlementEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LedgerEntry> {
    val byTransaction = splits.groupBy { it.transactionId }
    val balances = balancesOf(splits.filter { it.owedBy != null }, transactions, settlements)
    return transactions.flatMap { it.ledgerEntries(byTransaction[it.id].orEmpty()) } + Balances.differenceEntries(balances, zone)
}

/** What counts toward the everyday number, from these categories' switches (deleted ones included). */
fun List<CategoryEntity>.counting(): Counting {
    val parents = associate { it.id to it.parentId }
    return Counting(filter { !it.counted }.map { it.id }.toSet()) { parents[it] }
}

/** Whether [this] transaction counts toward the everyday number, before splits. */
fun TransactionEntity.counts(counting: Counting): Boolean =
    kind == Kind.EXPENSE.name && (counted ?: counting.categoryCounts(categoryId))

/**
 * What to call a transaction with no merchant: what the bank said it was ("Withdrawal",
 * "Deposit"), a transfer's note ("Credit card payment"), or "Card purchase" from a wallet.
 */
fun TransactionEntity.fallbackTitle(): String = when {
    kind == Kind.TRANSFER.name -> note ?: "Transfer"
    amountCents < 0 -> "Deposit"
    source == Source.RBC_ALERT.name -> "Withdrawal"
    source == Source.MANUAL.name -> "Uncategorized"
    else -> "Card purchase"
}

/** Money out as "$4.25", money in (refunds, deposits) as "+$4.25". */
fun displayAmount(cents: Long): String = if (cents < 0) "+${Money.format(-cents)}" else Money.format(cents)

/** When it happened, in the zone it happened in. */
fun TransactionEntity.occurredAtLocal(): ZonedDateTime =
    Instant.ofEpochMilli(occurredAt).atZone(ZoneId.of(zoneId))

private val DAY_THIS_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d")
private val DAY_OTHER_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> (if (date.year == today.year) DAY_THIS_YEAR else DAY_OTHER_YEAR).format(date)
}
