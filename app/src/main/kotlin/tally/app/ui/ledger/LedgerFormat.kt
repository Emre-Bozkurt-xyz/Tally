package tally.app.ui.ledger

import tally.app.data.SplitEntity
import tally.app.data.TransactionEntity
import tally.core.model.Kind
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
    LedgerEntry(amountCents, Kind.valueOf(kind), categoryId, occurredAtLocal().toLocalDate())
        .withSplits(splits.map { SplitPart(it.amountCents, it.categoryId, it.owedBy) })

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
