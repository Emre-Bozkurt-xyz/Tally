package tally.core.report

import tally.core.model.Kind
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** Part of a purchase someone owes you. */
data class Owed(
    val id: String,
    /** As written on the split ("Sam", "Sam Lee"). */
    val person: String,
    val amountCents: Long,
    /** When the purchase happened, epoch ms. */
    val occurredAt: Long,
    /** The purchase's category: where a "Call it even" shortfall lands. */
    val categoryId: String? = null,
    val transactionId: String? = null,
    /** Marked paid one by one before balances existed (`splits.settledAt`); stays paid. */
    val settledBefore: Boolean = false,
)

/** Money a person paid back, against their balance rather than one item. */
data class Repayment(
    val id: String,
    val person: String,
    val amountCents: Long,
    /** What "Call it even" absorbed: positive when they paid less than they owed, negative when more. */
    val differenceCents: Long,
    val occurredAt: Long,
    /** The deposit that paid it; null for cash or "Settle up". */
    val transactionId: String? = null,
)

/** What one person owes you, all told. */
data class Balance(
    /** The fullest name they've been written as ("Sam Lee" over "Sam"). */
    val person: String,
    /** Every owed part, newest first, including ones already paid. */
    val items: List<Owed>,
    /** Their payments, newest first. */
    val repayments: List<Repayment>,
) {
    /** Owed parts not marked paid the old way. */
    val owedCents: Long = items.filter { !it.settledBefore }.sumOf { it.amountCents }

    /** Payments, plus what calling it even absorbed. */
    val paidCents: Long = repayments.sumOf { it.amountCents + it.differenceCents }

    /** Positive: they owe you. Negative: you hold credit for them. */
    val balanceCents: Long = owedCents - paidCents

    /**
     * Parts payments haven't covered yet, with what's left of each. Payments cover the oldest
     * parts first, so a bulk or partial payment needs no bookkeeping.
     */
    val open: List<Pair<Owed, Long>> = run {
        var paid = paidCents
        items.filter { !it.settledBefore }.sortedBy { it.occurredAt }.mapNotNull { part ->
            val covered = paid.coerceIn(0, part.amountCents)
            paid -= covered
            (part to part.amountCents - covered).takeIf { it.second > 0 }
        }.reversed()
    }

    /** Whether [part] has been paid back, by payments (oldest first) or the old per-item way. */
    fun isPaid(part: Owed): Boolean = part.settledBefore || open.none { it.first.id == part.id }
}

/** What a payment of some amount would do to a balance. */
sealed interface Offer {
    /** Exactly what they owe. */
    data object Exact : Offer

    /**
     * Close enough to call it even: settle the whole balance, the [differenceCents] absorbed.
     * Positive [differenceCents] is a shortfall (theirs to keep owing), negative an overpayment.
     */
    data class Close(val differenceCents: Long) : Offer

    /** Less than they owe, by more than the slack: [leftCents] stays owing. */
    data class Partial(val leftCents: Long) : Offer

    /** More than they owe, by more than the slack: [creditCents] would be held as credit. */
    data class Over(val creditCents: Long) : Offer
}

object Balances {
    /** How far off a payment can be and still be called even: $5 or 10%, whichever is larger. */
    fun slackCents(balanceCents: Long): Long = max(500, abs(balanceCents) / 10)

    fun offer(balanceCents: Long, receivedCents: Long): Offer {
        val difference = balanceCents - receivedCents
        return when {
            difference == 0L -> Offer.Exact
            abs(difference) <= slackCents(balanceCents) -> Offer.Close(difference)
            difference > 0 -> Offer.Partial(difference)
            else -> Offer.Over(-difference)
        }
    }

    /** One balance per person ("Sam" and "Sam Lee" are one), who owes most first. */
    fun of(owed: List<Owed>, repayments: List<Repayment>): List<Balance> {
        val people = (owed.map { it.person } + repayments.map { it.person }).groupBy { People.key(it) }
        val owedBy = owed.groupBy { People.key(it.person) }
        val paidBy = repayments.groupBy { People.key(it.person) }
        return people.filterKeys { it.isNotEmpty() }.map { (key, names) ->
            Balance(
                person = People.fullest(names),
                items = owedBy[key].orEmpty().sortedByDescending { it.occurredAt },
                repayments = paidBy[key].orEmpty().sortedByDescending { it.occurredAt },
            )
        }.sortedWith(compareByDescending<Balance> { it.balanceCents }.thenBy { it.person.lowercase() })
    }

    /** The person money from [sender] ("SAM LEE" on an e-Transfer) comes from, if they have a balance. */
    fun forSender(sender: String?, balances: List<Balance>): Balance? {
        if (sender.isNullOrBlank()) return null
        return balances.firstOrNull { People.same(sender, it.person) }
    }

    /**
     * What calling it even did to your spending, as report entries: a shortfall is spending in
     * the category of the most recent thing they owed for (before the payment), an overpayment
     * money back there.
     */
    fun differenceEntries(balances: List<Balance>, zone: ZoneId): List<LedgerEntry> =
        balances.flatMap { balance ->
            balance.repayments.filter { it.differenceCents != 0L }.map { repayment ->
                val part = balance.items.firstOrNull { it.occurredAt <= repayment.occurredAt } ?: balance.items.firstOrNull()
                LedgerEntry(
                    amountCents = repayment.differenceCents,
                    kind = Kind.EXPENSE,
                    categoryId = part?.categoryId,
                    date = Instant.ofEpochMilli(repayment.occurredAt).atZone(zone).toLocalDate(),
                )
            }
        }
}

/** Names as people write them: "Sam", "Sam Lee" and "SAM LEE" are one person. */
object People {
    /** First name, lowercased: what two spellings of one person share. */
    fun key(name: String): String = words(name).firstOrNull().orEmpty()

    /** Same first name, or every word of one name appears in the other ("Lee Sam" on a bank's records). */
    fun same(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.first() == wb.first() || wa.containsAll(wb) || wb.containsAll(wa)
    }

    /** The most complete spelling, as the user typed it: "Sam Lee" over "Sam". */
    fun fullest(names: List<String>): String =
        names.map { it.trim() }.filter { it.isNotEmpty() }
            .maxWithOrNull(compareBy<String> { words(it).size }.thenBy { it.any(Char::isLowerCase) }.thenBy { it.length })
            ?: ""

    /** Up to [limit] people, most recent first, one entry per person, from names newest first. */
    fun recent(namesNewestFirst: List<String>, limit: Int = 2): List<String> =
        namesNewestFirst.groupBy { key(it) }.filterKeys { it.isNotEmpty() }.values
            .take(limit)
            .map { fullest(it) }

    private fun words(name: String) = name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
}

/** Their share when splitting [amountCents] [ways] ways: you keep the odd cent. */
fun shareOf(amountCents: Long, ways: Int): Long = amountCents / ways
