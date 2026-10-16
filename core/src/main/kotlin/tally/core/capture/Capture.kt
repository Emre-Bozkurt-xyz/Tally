package tally.core.capture

import tally.core.model.Kind
import tally.core.model.Source
import java.time.Instant

/** The parts of a posted notification that parsers look at. */
data class RawNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val postedAt: Instant,
)

/** Hints a parser can extract about which account a capture belongs to. */
data class AccountHint(
    val last4: String? = null,
    val accountName: String? = null,
)

/** A parsed, not yet normalized or deduplicated transaction candidate. */
data class Capture(
    val amountCents: Long,
    val merchantRaw: String?,
    val occurredAt: Instant,
    val source: Source,
    val accountHint: AccountHint?,
    val kind: Kind,
)

sealed interface ParseResult {
    data class Parsed(val capture: Capture) : ParseResult

    /** Recognized, but intentionally not a transaction (declined, promo, balance alert...). */
    data class Ignored(val reason: String) : ParseResult

    /** From a watched package but no known shape matched. Surfaces in the inbox. */
    data class Failed(val reason: String) : ParseResult
}

interface CaptureParser {
    val packageName: String

    fun parse(n: RawNotification): ParseResult
}
