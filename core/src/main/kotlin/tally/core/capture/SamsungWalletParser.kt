package tally.core.capture

import tally.core.model.Kind
import tally.core.model.Source

/**
 * Samsung Wallet (Samsung Pay) notifications.
 *
 * Known shapes (from real notifications):
 * - Purchase: title is the card ("RBC Visa Card/Carte Visa RBC‡"), text is the merchant then
 *   the amount ("PASHA LOUNGE CAD 31.30"). No last 4, so the account comes from the card name.
 * - Rewards: "You earned 8 points. Tap here to check your Samsung Rewards points." Ignored.
 */
object SamsungWalletParser : CaptureParser {
    override val packageName = WatchedPackages.SAMSUNG_WALLET

    private const val MONEY = "(?:CAD|CA\\$|C\\$|\\$)\\s*-?[\\d.,]+|-?[\\d.,]+\\s*(?:\\$|CAD)"
    private val PURCHASE = Regex("^(.+?)\\s+($MONEY)$")
    private val REWARDS = Regex("(?i)\\b(?:earned|redeemed)\\b.*\\bpoints?\\b|samsung rewards")

    override fun parse(n: RawNotification): ParseResult {
        val text = (n.bigText ?: n.text)?.trim()?.ifEmpty { null }
            ?: return ParseResult.Failed("Samsung Wallet notification with no text")
        if (REWARDS.containsMatchIn(text)) return ParseResult.Ignored("Samsung Rewards points")

        PURCHASE.find(text)?.let { match ->
            val cents = Amounts.parseCents(match.groupValues[2])
                ?: return ParseResult.Failed("Samsung Wallet purchase with unreadable amount: ${match.groupValues[2]}")
            return ParseResult.Parsed(
                Capture(
                    amountCents = cents,
                    merchantRaw = match.groupValues[1].trim(),
                    occurredAt = n.postedAt,
                    source = Source.SAMSUNG_WALLET,
                    accountHint = n.title?.trim()?.ifEmpty { null }?.let { AccountHint(accountName = it) },
                    kind = Kind.EXPENSE,
                ),
            )
        }

        return ParseResult.Failed("Unknown Samsung Wallet notification shape")
    }
}
