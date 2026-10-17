package tally.core.model

/**
 * What money in means once you file it: under a top-level category named "Income" it's
 * income (paycheque, tax refund); under a spending category it's money back for that
 * spending (a friend's share of dinner), which lowers that category's total like a refund.
 */
object CategoryKinds {
    const val INCOME = "Income"

    fun isIncome(topLevelName: String?): Boolean = topLevelName.equals(INCOME, ignoreCase = true)

    /** The kind after filing under a category. Transfers between your own accounts stay transfers. */
    fun kindFor(current: Kind, amountCents: Long, incomeCategory: Boolean): Kind = when {
        current == Kind.TRANSFER -> Kind.TRANSFER
        amountCents < 0 && incomeCategory -> Kind.INCOME
        else -> Kind.EXPENSE
    }
}
