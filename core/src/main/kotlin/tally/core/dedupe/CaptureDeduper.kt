package tally.core.dedupe

import tally.core.capture.Capture
import tally.core.model.Kind
import tally.core.model.Source
import java.time.Duration

/** An existing captured transaction that a new capture might be another notification of. */
data class DedupeCandidate(
    val transactionId: String,
    val amountCents: Long,
    val occurredAt: java.time.Instant,
    /** Sources of the notifications already attached to it. */
    val sources: Set<Source>,
    val accountId: String?,
    val kind: Kind = Kind.EXPENSE,
)

/**
 * One purchase often produces two notifications: the wallet's (with the merchant) and the
 * bank's (with the account). This finds the transaction a new capture belongs to.
 *
 * Match = same amount, within [window], from a source not already attached (two notifications
 * from the same app are two purchases), and no conflicting account. Closest in time wins.
 *
 * The exception is a credit card payment: RBC sends the chequing withdrawal and the payment
 * seconds apart, both from the same app. A payment pairs with a bank-only withdrawal of the
 * same amount (either order), across accounts, and the two become one transfer.
 */
class CaptureDeduper(private val window: Duration = Duration.ofMinutes(10)) {
    fun findMatch(capture: Capture, accountId: String?, candidates: List<DedupeCandidate>): DedupeCandidate? =
        candidates
            .filter { it.amountCents == capture.amountCents }
            .filter { Duration.between(it.occurredAt, capture.occurredAt).abs() <= window }
            .filter { if (isPaymentPair(capture, it)) true else sameEvent(capture, accountId, it) }
            .minByOrNull { Duration.between(it.occurredAt, capture.occurredAt).abs() }

    /** A purchase seen by another app: never merges into a transfer, or a transfer into one. */
    private fun sameEvent(capture: Capture, accountId: String?, candidate: DedupeCandidate): Boolean =
        capture.kind != Kind.TRANSFER && candidate.kind != Kind.TRANSFER &&
            capture.source !in candidate.sources &&
            (accountId == null || candidate.accountId == null || candidate.accountId == accountId)

    /** A card payment and the withdrawal paying it, both from the bank alone. */
    private fun isPaymentPair(capture: Capture, candidate: DedupeCandidate): Boolean {
        if (capture.amountCents <= 0 || capture.source != Source.RBC_ALERT || candidate.sources != setOf(Source.RBC_ALERT)) return false
        return (capture.kind == Kind.TRANSFER && candidate.kind == Kind.EXPENSE) ||
            (capture.kind == Kind.EXPENSE && candidate.kind == Kind.TRANSFER)
    }

    companion object {
        /** Wallets name the merchant better than banks, which truncate and uppercase it. */
        fun prefersMerchantFrom(source: Source): Boolean =
            source == Source.SAMSUNG_WALLET || source == Source.GOOGLE_WALLET
    }
}
