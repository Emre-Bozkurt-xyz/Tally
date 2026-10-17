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
 */
object RbcParser : CaptureParser {
    override val packageName = WatchedPackages.RBC

    private val WITHDRAWAL = Regex(
        "(?i)a withdrawal of (\\S+) was made from your RBC account \\*+(\\d{4})",
    )

    override fun parse(n: RawNotification): ParseResult {
        val body = n.bigText ?: n.text ?: return ParseResult.Failed("RBC notification with no text")

        WITHDRAWAL.find(body)?.let { match ->
            val cents = Amounts.parseCents(match.groupValues[1])
                ?: return ParseResult.Failed("RBC withdrawal with unreadable amount: ${match.groupValues[1]}")
            return ParseResult.Parsed(
                Capture(
                    amountCents = cents,
                    merchantRaw = null,
                    occurredAt = n.postedAt,
                    source = Source.RBC_ALERT,
                    accountHint = AccountHint(last4 = match.groupValues[2]),
                    kind = Kind.EXPENSE,
                ),
            )
        }

        return ParseResult.Failed("Unknown RBC notification shape")
    }
}
