package tally.core.capture

import tally.core.model.Kind
import tally.core.model.Source

/**
 * Google Wallet tap-to-pay notifications.
 *
 * Known shapes (from real notifications):
 * - Purchase: title is the merchant ("FOODIE FRUITIE"), text is
 *   "CA$34.32 with Royal Bank Debit/Débit ••3881". The last 4 is the card number, not the
 *   account number RBC alerts show.
 * - Passes and tickets ("HALLOWEEN ON KING @PHIL'S ticket", blank text): ignored, like anything
 *   else without an amount.
 */
object GoogleWalletParser : CaptureParser {
    override val packageName = WatchedPackages.GOOGLE_WALLET

    private val PURCHASE = Regex("^(\\S+) with (.+?)\\s*[•·*]+\\s*(\\d{4})$")

    override fun parse(n: RawNotification): ParseResult {
        val text = (n.text ?: n.bigText)?.trim()?.ifEmpty { null }
            ?: return ParseResult.Ignored("Google Wallet notification with no text (a pass or ticket)")

        PURCHASE.find(text)?.let { match ->
            val cents = Amounts.parseCents(match.groupValues[1])
                ?: return ParseResult.Failed("Google Wallet purchase with unreadable amount: ${match.groupValues[1]}")
            return ParseResult.Parsed(
                Capture(
                    amountCents = cents,
                    merchantRaw = n.title?.trim()?.ifEmpty { null },
                    occurredAt = n.postedAt,
                    source = Source.GOOGLE_WALLET,
                    accountHint = AccountHint(last4 = match.groupValues[3], accountName = match.groupValues[2]),
                    kind = Kind.EXPENSE,
                ),
            )
        }

        // Purchases always carry an amount; anything else is the wallet talking about itself.
        if (Amounts.findFirst(text) == null) return ParseResult.Ignored("Google Wallet notification with no amount")
        return ParseResult.Failed("Unknown Google Wallet notification shape")
    }
}
