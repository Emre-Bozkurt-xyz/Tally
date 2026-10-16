package tally.app.ui.ledger

import tally.app.data.TransactionEntity
import tally.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
