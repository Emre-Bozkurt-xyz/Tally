package tally.core.capture

import tally.core.model.Kind
import tally.core.model.Source

/**
 * Interac e-Transfer texts, from whichever messaging app shows them. Only these are ever
 * recorded from a messaging app (see [WatchedPackages.wanted]); anything else is ignored.
 *
 * Known shapes (from real messages):
 * - Received: "INTERAC e-Transfer: $70.00 (CAD) from Jane Doe has been deposited to your
 *   account". Names the sender, which RBC's deposit alert doesn't; the two merge into one
 *   deposit that can settle what that person owes.
 */
object InteracSmsParser {
    private val INTERAC = Regex("(?i)^\\s*INTERAC e-Transfer\\b")
    private val RECEIVED = Regex("(?i)INTERAC e-Transfer:\\s*(\\S+)\\s*(?:\\(CAD\\))?\\s*from (.+?) has been deposited")

    fun isInterac(text: String?): Boolean = text != null && INTERAC.containsMatchIn(text)

    fun parse(n: RawNotification): ParseResult {
        val text = (n.text ?: n.bigText)?.trim()
        if (!isInterac(text)) return ParseResult.Ignored("Not an Interac e-Transfer message")

        RECEIVED.find(text!!)?.let { match ->
            val cents = Amounts.parseCents(match.groupValues[1])
                ?: return ParseResult.Failed("Interac deposit with unreadable amount: ${match.groupValues[1]}")
            return ParseResult.Parsed(
                Capture(
                    amountCents = -cents,
                    merchantRaw = match.groupValues[2].trim(),
                    occurredAt = n.postedAt,
                    source = Source.INTERAC,
                    accountHint = null,
                    kind = Kind.INCOME,
                ),
            )
        }
        return ParseResult.Failed("Unknown Interac e-Transfer message")
    }
}
