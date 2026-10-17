package tally.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tally.core.normalize.MerchantNormalizer
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import tally.core.entry.CategoryRanking
import tally.core.entry.CategoryUse
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.Money
import tally.core.model.Source
import tally.core.model.TransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LedgerRepository(
    private val db: TallyDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val memory: CaptureMemory = CaptureMemory(db, clock),
) {
    private val transactions = db.transactionDao()

    fun observeCategories(): Flow<List<CategoryEntity>> = db.categoryDao().observeActive()

    fun observeAccounts(): Flow<List<AccountEntity>> = db.accountDao().observeActive()

    /** Including deleted ones, for labelling old transactions. */
    fun observeAllCategories(): Flow<List<CategoryEntity>> = db.categoryDao().observeAll()

    fun observeAllAccounts(): Flow<List<AccountEntity>> = db.accountDao().observeAll()

    fun observeTransactions(): Flow<List<TransactionEntity>> = transactions.observeActive()

    fun observeNeedsReview(): Flow<List<TransactionEntity>> = transactions.observeNeedsReview()

    /** Bank and wallet notifications Tally couldn't read (say, RBC changed its wording). */
    fun observeUnreadNotifications(): Flow<List<RawEventEntity>> = db.rawEventDao().observeFailed(WatchedPackages.ALL)

    fun observeTransaction(id: String): Flow<TransactionEntity?> = transactions.observeById(id)

    /** The bank and wallet notifications a transaction was built from. */
    fun observeNotificationsFor(transactionId: String): Flow<List<RawEventEntity>> =
        db.rawEventDao().observeLinkedTo(transactionId)

    // --- Tags ---

    fun observeTags(): Flow<List<TagEntity>> = db.tagDao().observeActive()

    fun observeTagLinks(): Flow<List<TransactionTagCrossRef>> = db.tagDao().observeLinks()

    fun observeTagsFor(transactionId: String): Flow<List<TagEntity>> = db.tagDao().observeFor(transactionId)

    /** Tags a transaction, creating the tag if it's new. Returns an error, or null. */
    suspend fun addTag(transactionId: String, name: String): String? {
        val trimmed = name.trim().removePrefix("#")
        if (trimmed.isEmpty()) return "Enter a tag"
        if (',' in trimmed) return "Tags can't contain commas"
        val now = clock.millis()
        val tags = db.tagDao()
        val tag = tags.findByName(trimmed) ?: TagEntity(UUID.randomUUID().toString(), trimmed, now).also { tags.upsert(it) }
        tags.link(TransactionTagCrossRef(transactionId, tag.id, now))
        return null
    }

    suspend fun removeTag(transactionId: String, tagId: String) = db.tagDao().unlink(transactionId, tagId, clock.millis())

    // --- Splits ---

    fun observeSplitsFor(transactionId: String): Flow<List<SplitEntity>> = db.splitDao().observeFor(transactionId)

    fun observeAllSplits(): Flow<List<SplitEntity>> = db.splitDao().observeAll()

    /**
     * Splits off part of a purchase: into another category, or as money [owedBy] someone.
     * Parts can't add up to more than the purchase. Returns an error, or null.
     */
    suspend fun addSplit(transactionId: String, amountCents: Long, categoryId: String?, owedBy: String?): String? {
        val transaction = transactions.getById(transactionId) ?: return "That transaction no longer exists"
        if (transaction.amountCents <= 0) return "Only purchases can be split"
        if (amountCents <= 0) return "Enter an amount"
        val owed = owedBy?.trim()?.ifEmpty { null }
        if (owed == null && categoryId == null) return "Pick a category or who owes it"
        val used = db.splitDao().getFor(transactionId).sumOf { it.amountCents }
        if (used + amountCents > transaction.amountCents) {
            return "Only ${Money.format(transaction.amountCents - used)} left to split"
        }
        db.splitDao().upsert(SplitEntity(UUID.randomUUID().toString(), transactionId, amountCents, categoryId.takeIf { owed == null }, owed, clock.millis()))
        return null
    }

    suspend fun deleteSplit(id: String) = db.splitDao().delete(id, clock.millis())

    fun observeOwed(): Flow<List<SplitEntity>> = db.splitDao().observeOwed()

    /** Marks an owed portion paid back (or not, to undo). */
    suspend fun setSettled(splitId: String, settled: Boolean) {
        val now = clock.millis()
        db.splitDao().setSettled(splitId, if (settled) now else null, now)
    }

    /** How many things wait in the inbox. */
    fun observeInboxCount(): Flow<Int> =
        combine(observeNeedsReview(), observeUnreadNotifications()) { review, unread -> review.size + unread.size }

    /** "Seen it": drops an unreadable notification from the inbox (and from re-parse). */
    suspend fun dismissUnreadNotification(rawEventId: String) =
        db.rawEventDao().markParsed(rawEventId, ParseStatus.IGNORED.name, null, clock.millis())

    /** Including deleted ones, for labelling old transactions. */
    fun observeAllMerchants(): Flow<List<MerchantEntity>> = db.merchantDao().observeAll()

    /**
     * You accepted a capture as suggested (tapped its notification). Returns false if it was
     * already handled or deleted.
     */
    suspend fun confirmCapture(id: String): Boolean {
        val transaction = transactions.getById(id) ?: return false
        if (transaction.status != TransactionStatus.CAPTURED.name || transaction.categoryId == null) return false
        transactions.upsert(transaction.copy(status = TransactionStatus.CONFIRMED.name, updatedAt = clock.millis()))
        memory.confirmCategory(transaction.merchantId, transaction.categoryId)
        memory.learnAccount(transaction.id, transaction.accountId)
        return true
    }

    suspend fun getTransaction(id: String): TransactionEntity? = transactions.getById(id)

    suspend fun transactionsSince(since: Instant): List<TransactionEntity> =
        transactions.getActiveSince(since.toEpochMilli())

    suspend fun deleteTransaction(id: String) = transactions.setDeleted(id, deleted = true, updatedAt = clock.millis())

    suspend fun restoreTransaction(id: String) = transactions.setDeleted(id, deleted = false, updatedAt = clock.millis())

    /** Category uses inside [CategoryRanking.WINDOW_DAYS], for ordering the quick-entry chips. */
    suspend fun recentCategoryUses(): List<CategoryUse> {
        val since = clock.instant().minus(Duration.ofDays(CategoryRanking.WINDOW_DAYS))
        return transactions.categoryUsesSince(since.toEpochMilli()).map {
            CategoryUse(it.categoryId, Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.of(it.zoneId)))
        }
    }

    /**
     * Saves a quick entry. With [existing], updates its amount, category, account and note and
     * marks it confirmed; otherwise creates a manual expense happening now.
     *
     * [merchantName] is what you typed for a manual entry: null keeps the merchant as it is,
     * blank removes it. It goes through your aliases like a captured name, so "tims" can land
     * on the same merchant as the card captures. A capture's merchant always stays the one
     * the notification named.
     */
    suspend fun saveQuickEntry(
        existing: TransactionEntity?,
        amountCents: Long,
        categoryId: String,
        accountId: String?,
        note: String?,
        merchantName: String? = null,
    ): TransactionEntity {
        val now = clock.millis()
        val manual = existing == null || existing.source == Source.MANUAL.name
        val typed = merchantName?.trim()?.takeIf { manual }
        val merchant = typed?.takeIf { it.isNotEmpty() }?.let { merchantFor(it, now) }
        val transaction = existing?.copy(
            amountCents = amountCents,
            categoryId = categoryId,
            accountId = accountId,
            note = note,
            status = TransactionStatus.CONFIRMED.name,
            categorySource = CategorySource.MANUAL.name,
            updatedAt = now,
        )?.let { if (typed == null) it else it.copy(merchantId = merchant?.id, merchantRaw = merchant?.let { typed }) }
            ?: TransactionEntity(
            id = UUID.randomUUID().toString(),
            amountCents = amountCents,
            currency = CURRENCY,
            occurredAt = now,
            zoneId = clock.zone.id,
            accountId = accountId,
            merchantId = merchant?.id,
            merchantRaw = merchant?.let { typed },
            categoryId = categoryId,
            note = note,
            source = Source.MANUAL.name,
            status = TransactionStatus.CONFIRMED.name,
            categorySource = CategorySource.MANUAL.name,
            kind = Kind.EXPENSE.name,
            updatedAt = now,
        )
        transactions.upsert(transaction)
        if (existing == null) {
            memory.confirmCategory(transaction.merchantId, categoryId)
        } else {
            // Count it once per decision: confirming a capture, a new category, or a new merchant.
            if (existing.status == TransactionStatus.CAPTURED.name || existing.categoryId != categoryId ||
                existing.merchantId != transaction.merchantId
            ) {
                memory.confirmCategory(transaction.merchantId, categoryId)
            }
            if (existing.accountId != accountId) memory.learnAccount(existing.id, accountId)
        }
        return transaction
    }

    /** Known merchants for the quick entry suggestions: most confirmed first. */
    fun observeMerchantChoices(): Flow<List<MerchantEntity>> = db.merchantDao().observeAll()
        .map { all -> all.filter { !it.deleted }.sortedWith(compareByDescending<MerchantEntity> { it.confirmCount }.thenBy { it.name.lowercase() }) }

    /** The merchant a typed name normalizes to, created if new (as a capture would). */
    private suspend fun merchantFor(typed: String, now: Long): MerchantEntity {
        val name = MerchantNormalizer(db.aliasDao().getActive().mapNotNull { it.toAlias() }).normalize(typed)
        val merchants = db.merchantDao()
        return merchants.findByName(name)
            ?: MerchantEntity(UUID.randomUUID().toString(), name, null, 0, now).also { merchants.upsert(it) }
    }

    companion object {
        const val CURRENCY = "CAD"
    }
}
