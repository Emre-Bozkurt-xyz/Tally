package tally.core.statement

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A transaction already in Tally that a statement row might be. */
data class MatchCandidate(
    val id: String,
    val amountCents: Long,
    val date: LocalDate,
    /** Merchant name and/or raw text, for telling same-amount purchases apart. */
    val merchant: String?,
)

data class StatementMatches(
    /** Row index to the transaction it is. */
    val matched: Map<Int, MatchCandidate>,
    /** Rows that aren't in Tally yet. */
    val unmatched: List<Int>,
    /** Transactions in the statement's period that the statement doesn't have. */
    val missing: List<MatchCandidate>,
)

/**
 * Pairs statement rows with the transactions Tally already has, one to one. A pair needs the
 * same amount (or within 25% when the merchant clearly agrees: tips, pre-auth holds) and a
 * date close by: a capture is dated when you paid, a chequing statement when it posted.
 * Best pairs are taken first.
 */
object StatementMatcher {
    /** Posting can lag the purchase; a capture can't come after the statement's date by much. */
    private const val DAYS_BEFORE = 5L
    private const val DAYS_AFTER = 1L

    fun match(lines: List<StatementLine>, candidates: List<MatchCandidate>, from: LocalDate, to: LocalDate): StatementMatches {
        data class Pair(val line: Int, val candidate: MatchCandidate, val score: Double)

        val pairs = lines.flatMapIndexed { i, line ->
            candidates.mapNotNull { c -> score(line, c)?.let { Pair(i, c, it) } }
        }.sortedByDescending { it.score }

        val matched = mutableMapOf<Int, MatchCandidate>()
        val used = mutableSetOf<String>()
        for (p in pairs) {
            if (p.line in matched || p.candidate.id in used) continue
            matched[p.line] = p.candidate
            used += p.candidate.id
        }
        val missing = candidates.filter { it.id !in used && !it.date.isBefore(from) && !it.date.isAfter(to) }
        return StatementMatches(matched, lines.indices.filter { it !in matched }, missing)
    }

    /** Null when they can't be the same transaction; otherwise higher is likelier. */
    internal fun score(line: StatementLine, c: MatchCandidate): Double? {
        val days = ChronoUnit.DAYS.between(c.date, line.date) // Positive: the statement is later.
        if (days > DAYS_BEFORE || days < -DAYS_AFTER) return null
        if (line.amountCents == 0L || (line.amountCents > 0) != (c.amountCents > 0)) return null
        val similarity = similarity(line.merchant, c.merchant)
        val amount = when {
            line.amountCents == c.amountCents -> 1.0
            abs(line.amountCents - c.amountCents) <= abs(line.amountCents) / 4 && similarity >= 0.5 -> 0.5
            else -> return null
        }
        val closeness = 1.0 - abs(days) / (DAYS_BEFORE + 1.0)
        return amount * 0.6 + closeness * 0.25 + similarity * 0.15
    }

    /**
     * How alike two merchant texts are, 0 to 1: shared words, or one squashed name inside the
     * other ("DOORDASHWENDYS" has "wendys"). Bank text is truncated and shouty, so it's loose.
     */
    internal fun similarity(a: String?, b: String?): Double {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size)
        val squashedA = ta.joinToString("")
        val squashedB = tb.joinToString("")
        val contained = tb.any { it.length >= 4 && it in squashedA } || ta.any { it.length >= 4 && it in squashedB }
        return maxOf(shared, if (contained) 0.6 else 0.0).coerceAtMost(1.0)
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().replace("'", "").split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in NOISE }.toSet()

    /** Words every card description has that say nothing about the merchant. */
    private val NOISE = setOf("on", "ca", "com", "www", "inc", "ltd", "the")
}
