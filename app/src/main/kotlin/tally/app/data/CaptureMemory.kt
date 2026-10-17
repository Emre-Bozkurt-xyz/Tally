package tally.app.data

import tally.core.capture.CaptureParsers
import tally.core.capture.ParseResult
import tally.core.rules.MerchantMemory
import java.time.Clock
import java.util.UUID

/**
 * What Tally learns from you: which category a merchant gets, and which last-4s belong to
 * which account. Updated when you confirm or edit a captured transaction.
 */
class CaptureMemory(
    private val db: TallyDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val merchants = db.merchantDao()
    private val identifiers = db.accountIdentifierDao()

    /** You confirmed [categoryId] for the merchant of a transaction. */
    suspend fun confirmCategory(merchantId: String?, categoryId: String?) {
        if (merchantId == null || categoryId == null) return
        val merchant = merchants.getById(merchantId) ?: return
        val next = MerchantMemory(merchant.lastCategoryId, merchant.confirmCount).confirmed(categoryId)
        merchants.upsert(
            merchant.copy(lastCategoryId = next.lastCategoryId, confirmCount = next.confirmCount, updatedAt = clock.millis()),
        )
    }

    /**
     * The transaction belongs to [accountId]: remember the last-4s its notifications showed, so
     * the next capture with any of them gets that account. Known last-4s are left alone.
     */
    suspend fun learnAccount(transactionId: String, accountId: String?) {
        if (accountId == null) return
        val last4s = db.rawEventDao().linkedTo(transactionId)
            .mapNotNull { (CaptureParsers.parse(it.toRawNotification()) as? ParseResult.Parsed)?.capture?.accountHint?.last4 }
            .toSet()
        for (last4 in last4s) {
            if (identifiers.accountFor(last4) == null) {
                identifiers.insert(
                    AccountIdentifierEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        last4 = last4,
                        updatedAt = clock.millis(),
                    ),
                )
            }
        }
    }
}
