package tally.core.report

import tally.core.model.Kind

/**
 * What counts toward the everyday number on Home and the widget: spending in every category
 * except the ones switched off (fixed costs like rent and bills) and their subcategories.
 * A transaction can override its category either way (`transactions.counted`): a laptop you
 * don't want in the week, or a bill you do. Uncategorized spending counts.
 *
 * [uncounted] holds the ids of categories switched off; [parentOf] maps a category to its
 * parent, or null for a top-level category.
 */
class Counting(private val uncounted: Set<String>, private val parentOf: (String) -> String?) {
    /** Whether spending in [categoryId] counts, before any override. */
    fun categoryCounts(categoryId: String?): Boolean {
        if (categoryId == null) return true
        if (categoryId in uncounted) return false
        return parentOf(categoryId)?.let { it !in uncounted } ?: true
    }

    /** Spending (refunds included, as negative amounts) that counts. Transfers and income never do. */
    fun counts(entry: LedgerEntry): Boolean = entry.kind == Kind.EXPENSE && (entry.counted ?: categoryCounts(entry.categoryId))

    companion object {
        /** Everything counts: no categories switched off. */
        val ALL = Counting(emptySet()) { null }
    }
}
