package tally.core.dedupe

import tally.core.capture.Capture
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
)

/**
 * One purchase often produces two notifications: the wallet's (with the merchant) and the
 * bank's (with the account). This finds the transaction a new capture belongs to.
 *
 * Match = same amount, within [window], from a source not already attached (two notifications
 * from the same app are two purchases), and no conflicting account. Closest in time wins.
 */
class CaptureDeduper(private val window: Duration = Duration.ofMinutes(10)) {
    fun findMatch(capture: Capture, accountId: String?, candidates: List<DedupeCandidate>): DedupeCandidate? =
        candidates
            .filter { it.amountCents == capture.amountCents }
            .filter { Duration.between(it.occurredAt, capture.occurredAt).abs() <= window }
            .filter { capture.source !in it.sources }
            .filter { accountId == null || it.accountId == null || it.accountId == accountId }
            .minByOrNull { Duration.between(it.occurredAt, capture.occurredAt).abs() }

    companion object {
        /** Wallets name the merchant better than banks, which truncate and uppercase it. */
        fun prefersMerchantFrom(source: Source): Boolean =
            source == Source.SAMSUNG_WALLET || source == Source.GOOGLE_WALLET
    }
}
