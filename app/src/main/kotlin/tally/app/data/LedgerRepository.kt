package tally.app.data

import kotlinx.coroutines.flow.Flow
import tally.core.entry.CategoryRanking
import tally.core.entry.CategoryUse
import tally.core.model.CategorySource
import tally.core.model.Kind
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
     */
    suspend fun saveQuickEntry(
        existing: TransactionEntity?,
        amountCents: Long,
        categoryId: String,
        accountId: String?,
        note: String?,
    ): TransactionEntity {
        val now = clock.millis()
        val transaction = existing?.copy(
            amountCents = amountCents,
            categoryId = categoryId,
            accountId = accountId,
            note = note,
            status = TransactionStatus.CONFIRMED.name,
            categorySource = CategorySource.MANUAL.name,
            updatedAt = now,
        ) ?: TransactionEntity(
            id = UUID.randomUUID().toString(),
            amountCents = amountCents,
            currency = CURRENCY,
            occurredAt = now,
            zoneId = clock.zone.id,
            accountId = accountId,
            merchantId = null,
            merchantRaw = null,
            categoryId = categoryId,
            note = note,
            source = Source.MANUAL.name,
            status = TransactionStatus.CONFIRMED.name,
            categorySource = CategorySource.MANUAL.name,
            kind = Kind.EXPENSE.name,
            updatedAt = now,
        )
        transactions.upsert(transaction)
        if (existing != null) {
            // Count it once per decision: confirming a capture, or changing the category.
            if (existing.status == TransactionStatus.CAPTURED.name || existing.categoryId != categoryId) {
                memory.confirmCategory(existing.merchantId, categoryId)
            }
            if (existing.accountId != accountId) memory.learnAccount(existing.id, accountId)
        }
        return transaction
    }

    companion object {
        const val CURRENCY = "CAD"
    }
}
