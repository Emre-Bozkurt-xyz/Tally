package tally.core.capture

import tally.core.model.Kind
import tally.core.model.Source

/**
 * RBC Mobile alerts.
 *
 * Known shapes (from real notifications):
 * - Chequing withdrawal: "A withdrawal of $34.32 was made from your RBC account ********8805
 *   on 10/17." Debit purchases arrive like this, with no merchant; the merchant comes from the
 *   wallet notification, if any. The date is RBC's (UTC), so `occurredAt` uses the post time.
 * - Deposit: "A deposit of $70.00 was made to your RBC account ********8805 on 11/07." No sender,
 *   so an e-Transfer can't be told from a paycheque: money in, for the inbox.
 * - Credit card payment: "A payment of $200.00 to RBC credit card 0800 on 11/02." Arrives
 *   seconds after the chequing withdrawal it pays with; together they're one transfer.
 * - Ignored: "RBC credit card 0800 is below set limit. Available credit: …" and "You're spending
 *   against your budget. …" (RBC's own budgeting nudge).
 */
object RbcParser : CaptureParser {
    override val packageName = WatchedPackages.RBC

    private val WITHDRAWAL = Regex("(?i)a withdrawal of (\\S+) was made from your RBC account \\*+(\\d{4})")
    private val DEPOSIT = Regex("(?i)a deposit of (\\S+) was made to your RBC account \\*+(\\d{4})")
    private val CARD_PAYMENT = Regex("(?i)a payment of (\\S+) to RBC credit card (\\d{4})")
    private val LOW_CREDIT = Regex("(?i)credit card \\d{4} is below set limit")
    private val BUDGET_NUDGE = Regex("(?i)spending against your budget")

    override fun parse(n: RawNotification): ParseResult {
        val body = n.bigText ?: n.text ?: return ParseResult.Failed("RBC notification with no text")

        WITHDRAWAL.find(body)?.let { match ->
            val cents = amount(match) ?: return unreadable("withdrawal", match)
            return parsed(n, cents, Kind.EXPENSE, AccountHint(last4 = match.groupValues[2]))
        }
        DEPOSIT.find(body)?.let { match ->
            val cents = amount(match) ?: return unreadable("deposit", match)
            return parsed(n, -cents, Kind.INCOME, AccountHint(last4 = match.groupValues[2]))
        }
        CARD_PAYMENT.find(body)?.let { match ->
            val cents = amount(match) ?: return unreadable("credit card payment", match)
            // No account hint: the card's last 4 isn't the account the money leaves. The
            // chequing withdrawal it pairs with brings that.
            return parsed(n, cents, Kind.TRANSFER, null)
        }
        if (LOW_CREDIT.containsMatchIn(body)) return ParseResult.Ignored("RBC low available credit alert")
        if (BUDGET_NUDGE.containsMatchIn(body)) return ParseResult.Ignored("RBC budgeting nudge")

        return ParseResult.Failed("Unknown RBC notification shape")
    }

    private fun amount(match: MatchResult) = Amounts.parseCents(match.groupValues[1])

    private fun unreadable(what: String, match: MatchResult) =
        ParseResult.Failed("RBC $what with unreadable amount: ${match.groupValues[1]}")

    private fun parsed(n: RawNotification, cents: Long, kind: Kind, hint: AccountHint?) = ParseResult.Parsed(
        Capture(
            amountCents = cents,
            merchantRaw = null,
            occurredAt = n.postedAt,
            source = Source.RBC_ALERT,
            accountHint = hint,
            kind = kind,
        ),
    )
}
