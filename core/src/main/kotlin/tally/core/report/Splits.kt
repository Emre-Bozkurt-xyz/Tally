package tally.core.report

import tally.core.model.Kind

/** Part of a transaction: its own category, or a portion [owedBy] someone else. */
data class SplitPart(val amountCents: Long, val categoryId: String?, val owedBy: String?)

/**
 * Expands a transaction with splits into what reports should count: each categorized part in
 * its category, the remainder in the transaction's category, and owed parts not at all (it's
 * money you're getting back, not spending; see "owed to me" in Phase 6).
 */
fun LedgerEntry.withSplits(parts: List<SplitPart>): List<LedgerEntry> {
    if (parts.isEmpty() || kind != Kind.EXPENSE) return listOf(this)
    val expanded = parts.map { part ->
        if (part.owedBy != null) {
            copy(amountCents = part.amountCents, kind = Kind.TRANSFER, categoryId = null)
        } else {
            copy(amountCents = part.amountCents, categoryId = part.categoryId ?: categoryId)
        }
    }
    val remainder = amountCents - parts.sumOf { it.amountCents }
    return if (remainder == 0L) expanded else expanded + copy(amountCents = remainder)
}
