package tally.app.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tally.core.capture.Capture
import tally.core.capture.CaptureParsers
import tally.core.capture.ParseResult
import tally.core.capture.WatchedPackages
import tally.core.dedupe.CaptureDeduper
import tally.core.dedupe.DedupeCandidate
import tally.core.model.ParseStatus
import tally.core.model.Source
import tally.core.model.TransactionStatus
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.Categorizer
import tally.core.rules.Confidence
import tally.core.rules.MerchantMemory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Turns a stored raw event into a transaction: parse, resolve the account, normalize the
 * merchant, merge with the other notification of the same purchase, categorize. All the
 * decisions live in `core`; this class only reads and writes.
 */
class CapturePipeline(
    private val db: TallyDatabase,
    private val memory: CaptureMemory,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val transactions = db.transactionDao()
    private val rawEvents = db.rawEventDao()
    private val merchants = db.merchantDao()
    private val identifiers = db.accountIdentifierDao()
    private val normalizer = MerchantNormalizer()
    private val deduper = CaptureDeduper(DEDUPE_WINDOW)

    // Notifications of one purchase arrive seconds apart; process them one at a time.
    private val lock = Mutex()

    sealed interface Outcome {
        /** Not from a watched package. */
        data object NotWatched : Outcome

        /** Recognized as not a transaction, or not recognized (FAILED: an inbox flag in Phase 3). */
        data class Marked(val status: ParseStatus, val reason: String) : Outcome

        data class Captured(
            val transaction: TransactionEntity,
            val merchantName: String?,
            val categoryName: String?,
        ) : Outcome {
            /** Still waiting for you: show (or update) the capture notification. */
            val needsReview get() = !transaction.deleted && transaction.status == TransactionStatus.CAPTURED.name
        }
    }

    suspend fun process(event: RawEventEntity): Outcome = lock.withLock {
        when (val result = CaptureParsers.parse(event.toRawNotification())) {
            null -> Outcome.NotWatched
            is ParseResult.Ignored -> {
                rawEvents.markParsed(event.id, ParseStatus.IGNORED.name, null)
                Outcome.Marked(ParseStatus.IGNORED, result.reason)
            }
            is ParseResult.Failed -> {
                rawEvents.markParsed(event.id, ParseStatus.FAILED.name, null)
                Outcome.Marked(ParseStatus.FAILED, result.reason)
            }
            is ParseResult.Parsed -> db.useWriterConnection { connection ->
                connection.immediateTransaction { capture(event, result.capture) }
            }
        }
    }

    /**
     * Runs every watched-package event that has no transaction yet through [process], oldest
     * first so the two notifications of a purchase merge in order. Used after parsers change.
     */
    suspend fun reparseUnlinked(): List<Outcome> =
        rawEvents.unlinked(WatchedPackages.ALL).map { process(it) }

    private suspend fun capture(event: RawEventEntity, capture: Capture): Outcome.Captured {
        val now = clock.millis()
        val accountId = capture.accountHint?.last4?.let { identifiers.accountFor(it) }
        val merchant = capture.merchantRaw?.let { merchantNamed(normalizer.normalize(it), now) }
        val match = deduper.findMatch(capture, accountId, candidatesNear(capture.occurredAt))

        val transaction = if (match == null) {
            create(event, capture, accountId, merchant, now)
        } else {
            merge(transactions.getByIdIncludingDeleted(match.transactionId)!!, capture, accountId, merchant, now)
        }
        rawEvents.markParsed(event.id, ParseStatus.PARSED.name, transaction.id)
        memory.learnAccount(transaction.id, transaction.accountId)

        val merchantName = transaction.merchantId?.let { merchants.getById(it)?.name }
        val categoryName = transaction.categoryId?.let { id ->
            db.categoryDao().getActive().find { it.id == id }?.name
        }
        return Outcome.Captured(transaction, merchantName, categoryName)
    }

    private suspend fun create(
        event: RawEventEntity,
        capture: Capture,
        accountId: String?,
        merchant: MerchantEntity?,
        now: Long,
    ): TransactionEntity {
        val transaction = TransactionEntity(
            id = UUID.randomUUID().toString(),
            amountCents = capture.amountCents,
            currency = LedgerRepository.CURRENCY,
            occurredAt = capture.occurredAt.toEpochMilli(),
            zoneId = event.zoneId,
            accountId = accountId,
            merchantId = merchant?.id,
            merchantRaw = capture.merchantRaw,
            categoryId = null,
            note = null,
            source = capture.source.name,
            status = TransactionStatus.CAPTURED.name,
            categorySource = null,
            kind = capture.kind.name,
            updatedAt = now,
        ).categorized(merchant)
        transactions.upsert(transaction)
        return transaction
    }

    /** Later notifications fill in what the first one lacked. */
    private suspend fun merge(
        existing: TransactionEntity,
        capture: Capture,
        accountId: String?,
        merchant: MerchantEntity?,
        now: Long,
    ): TransactionEntity {
        var merged = existing.copy(occurredAt = minOf(existing.occurredAt, capture.occurredAt.toEpochMilli()))
        if (merchant != null && (merged.merchantId == null || CaptureDeduper.prefersMerchantFrom(capture.source))) {
            merged = merged.copy(merchantId = merchant.id, merchantRaw = capture.merchantRaw)
        }
        // The bank knows the account for sure; a wallet's card can belong to either account.
        if (accountId != null && (merged.accountId == null || capture.source == Source.RBC_ALERT)) {
            merged = merged.copy(accountId = accountId)
        }
        if (merged.status == TransactionStatus.CAPTURED.name && merged.categoryId == null) {
            merged = merged.categorized(merchant ?: merged.merchantId?.let { merchants.getById(it) })
        }
        if (merged == existing) return existing
        return merged.copy(updatedAt = now).also { transactions.upsert(it) }
    }

    /** Applies merchant memory. Trusted memory confirms the transaction outright. */
    private fun TransactionEntity.categorized(merchant: MerchantEntity?): TransactionEntity {
        val suggestion = Categorizer.categorize(merchant?.let { MerchantMemory(it.lastCategoryId, it.confirmCount) })
        if (suggestion.categoryId == null) return this
        return copy(
            categoryId = suggestion.categoryId,
            categorySource = suggestion.source?.name,
            status = if (suggestion.confidence == Confidence.HIGH) TransactionStatus.CONFIRMED.name else status,
        )
    }

    private suspend fun merchantNamed(name: String, now: Long): MerchantEntity =
        merchants.findByName(name) ?: MerchantEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            lastCategoryId = null,
            confirmCount = 0,
            updatedAt = now,
        ).also { merchants.upsert(it) }

    private suspend fun candidatesNear(at: Instant): List<DedupeCandidate> =
        transactions.dedupeCandidates(
            from = at.minus(DEDUPE_WINDOW).toEpochMilli(),
            to = at.plus(DEDUPE_WINDOW).toEpochMilli(),
        ).map { row ->
            DedupeCandidate(
                transactionId = row.transactionId,
                amountCents = row.amountCents,
                occurredAt = Instant.ofEpochMilli(row.occurredAt),
                sources = row.rawSources.orEmpty().split(',').mapNotNull { WatchedPackages.sourceFor(it) }.toSet(),
                accountId = row.accountId,
            )
        }

    private companion object {
        val DEDUPE_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
