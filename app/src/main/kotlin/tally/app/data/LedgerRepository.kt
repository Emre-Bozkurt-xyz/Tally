package tally.app.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tally.core.capture.WatchedPackages
import tally.core.entry.CategoryRanking
import tally.core.entry.CategoryUse
import tally.core.model.CategoryKinds
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.Money
import tally.core.model.ParseStatus
import tally.core.model.Source
import tally.core.model.TransactionStatus
import tally.core.normalize.MerchantIds
import tally.core.normalize.MerchantNormalizer
import tally.core.report.Balance
import tally.core.report.Balances
import tally.core.report.Owed
import tally.core.report.People
import tally.core.report.Repayment
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
    suspend fun addSplit(transactionId: String, amountCents: Long, categoryId: String?, owedBy: String?): String? =
        newSplit(transactionId, amountCents, categoryId, owedBy).exceptionOrNull()?.message

    /** "½ with Sam": [cents] of a purchase owed by [person]. Returns the split's id (for undo), or an error. */
    suspend fun splitWith(transactionId: String, person: String, cents: Long): Result<String> =
        newSplit(transactionId, cents, null, person)

    private suspend fun newSplit(transactionId: String, amountCents: Long, categoryId: String?, owedBy: String?): Result<String> {
        fun error(message: String) = Result.failure<String>(IllegalArgumentException(message))
        val transaction = transactions.getById(transactionId) ?: return error("That transaction no longer exists")
        if (transaction.amountCents <= 0) return error("Only purchases can be split")
        if (amountCents <= 0) return error("Enter an amount")
        val owed = owedBy?.trim()?.ifEmpty { null }
        if (owed == null && categoryId == null) return error("Pick a category or who owes it")
        val used = db.splitDao().getFor(transactionId).sumOf { it.amountCents }
        if (used + amountCents > transaction.amountCents) {
            return error("Only ${Money.format(transaction.amountCents - used)} left to split")
        }
        val split = SplitEntity(UUID.randomUUID().toString(), transactionId, amountCents, categoryId.takeIf { owed == null }, owed, clock.millis())
        db.splitDao().upsert(split)
        return Result.success(split.id)
    }

    suspend fun deleteSplit(id: String) = db.splitDao().delete(id, clock.millis())

    fun observeOwed(): Flow<List<SplitEntity>> = db.splitDao().observeOwed()

    // --- Owed to me ---

    fun observeSettlements(): Flow<List<SettlementEntity>> = db.settlementDao().observeActive()

    /** Who owes you what: owed parts of live purchases minus what they paid back, one balance per person. */
    fun observeBalances(): Flow<List<Balance>> =
        combine(observeOwed(), observeTransactions(), observeSettlements()) { owed, live, settlements -> balancesOf(owed, live, settlements) }

    suspend fun balances(): List<Balance> = observeBalances().first()

    /** People you split with lately, newest first: the "½ with Sam" chips. */
    fun observeRecentPeople(limit: Int = 2): Flow<List<String>> = observeOwed().map { recentPeople(it, limit) }

    suspend fun recentPeople(limit: Int = 2): List<String> = recentPeople(db.splitDao().getOwed(), limit)

    /**
     * [person] paid you back [cents], against their balance. With [depositId], that deposit was
     * the money: it becomes a transfer (not income, not money back), since what they owed never
     * counted as your spending. [callItEven] settles their whole balance, the difference absorbed
     * as your spending (or money back). Returns the payment's id, for undo.
     */
    suspend fun settle(person: String, cents: Long, depositId: String? = null, callItEven: Boolean = false): String? {
        if (cents <= 0 || person.isBlank()) return null
        val balance = balances().firstOrNull { People.same(it.person, person) }
        val deposit = depositId?.let { transactions.getById(it) ?: return null }
        val now = clock.millis()
        val settlement = SettlementEntity(
            id = UUID.randomUUID().toString(),
            person = balance?.person ?: person.trim(),
            amountCents = cents,
            transactionId = deposit?.id,
            differenceCents = if (callItEven && balance != null) balance.balanceCents - cents else 0,
            occurredAt = deposit?.occurredAt ?: now,
            updatedAt = now,
        )
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                db.settlementDao().upsert(settlement)
                if (deposit != null) {
                    transactions.upsert(
                        deposit.copy(
                            kind = Kind.TRANSFER.name,
                            status = TransactionStatus.CONFIRMED.name,
                            categoryId = null,
                            categorySource = null,
                            note = deposit.note ?: paidBackNote(settlement.person),
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
        return settlement.id
    }

    /**
     * Takes a payment back. Its deposit returns to the inbox: as [restore] (what it was before
     * settling, for undo right after), or as money in to sort.
     */
    suspend fun undoSettlement(id: String, restore: TransactionEntity? = null) {
        val settlement = db.settlementDao().getById(id) ?: return
        val now = clock.millis()
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                db.settlementDao().delete(id, now)
                val deposit = settlement.transactionId?.let { transactions.getById(it) }
                when {
                    deposit == null -> {}
                    restore != null && restore.id == deposit.id -> transactions.upsert(restore.copy(updatedAt = now))
                    else -> transactions.upsert(
                        deposit.copy(
                            kind = Kind.INCOME.name,
                            status = TransactionStatus.CAPTURED.name,
                            note = deposit.note?.takeIf { it != paidBackNote(settlement.person) },
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
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

    /** Puts a transaction back as it was (undo), as a new edit so it syncs. */
    suspend fun restore(previous: TransactionEntity) = transactions.upsert(previous.copy(updatedAt = clock.millis()))

    /**
     * Money moving between your own accounts (a credit card payment RBC sent no card alert for):
     * not spending, not income. Picking a category later makes it spending again.
     */
    suspend fun markTransfer(id: String) {
        val transaction = transactions.getById(id) ?: return
        transactions.upsert(
            transaction.copy(
                kind = Kind.TRANSFER.name,
                categoryId = null,
                categorySource = null,
                status = TransactionStatus.CONFIRMED.name,
                updatedAt = clock.millis(),
            ),
        )
    }

    /** A capture as its notification shows it: names looked up. */
    suspend fun captured(id: String): CapturePipeline.Outcome.Captured? = db.captured(id)

    /**
     * Whether this one transaction counts toward the everyday number, whatever its category
     * says ([counted] null goes back to following the category).
     */
    suspend fun setCounted(id: String, counted: Boolean?) {
        val transaction = transactions.getById(id) ?: return
        if (transaction.counted != counted) transactions.upsert(transaction.copy(counted = counted, updatedAt = clock.millis()))
    }

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
        // Money in: income under "Income", otherwise money back for that spending. Choosing a
        // category for a transfer says it wasn't one.
        val kind = existing?.let {
            val current = Kind.valueOf(it.kind).let { k -> if (k == Kind.TRANSFER) Kind.EXPENSE else k }
            CategoryKinds.kindFor(current, amountCents, db.categoryDao().getActive().isIncome(categoryId))
        }
        val typed = merchantName?.trim()?.takeIf { manual }
        val merchant = typed?.takeIf { it.isNotEmpty() }?.let { merchantFor(it, now) }
        val transaction = existing?.copy(
            amountCents = amountCents,
            categoryId = categoryId,
            accountId = accountId,
            note = note,
            status = TransactionStatus.CONFIRMED.name,
            categorySource = CategorySource.MANUAL.name,
            kind = kind?.name ?: existing.kind,
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
            ?: MerchantEntity(MerchantIds.forName(name), name, null, 0, now).also { merchants.upsert(it) }
    }

    companion object {
        const val CURRENCY = "CAD"

        fun paidBackNote(person: String) = "$person paid back"
    }
}

/** Balances from the database's rows: owed parts of live purchases (deleted ones drop out) and payments. */
fun balancesOf(owed: List<SplitEntity>, live: List<TransactionEntity>, settlements: List<SettlementEntity>): List<Balance> {
    val byId = live.associateBy { it.id }
    return Balances.of(
        owed.mapNotNull { split ->
            val purchase = byId[split.transactionId] ?: return@mapNotNull null
            val person = split.owedBy ?: return@mapNotNull null
            Owed(split.id, person, split.amountCents, purchase.occurredAt, purchase.categoryId, purchase.id, settledBefore = split.settledAt != null)
        },
        settlements.filter { !it.deleted }.map { Repayment(it.id, it.person, it.amountCents, it.differenceCents, it.occurredAt, it.transactionId) },
    )
}

/** A capture as its notification shows it: names looked up. */
suspend fun TallyDatabase.captured(id: String): CapturePipeline.Outcome.Captured? {
    val transaction = transactionDao().getById(id) ?: return null
    val merchant = transaction.merchantId?.let { merchantDao().getById(it) }
    val category = transaction.categoryId?.let { categoryId -> categoryDao().getActive().firstOrNull { it.id == categoryId } }
    return CapturePipeline.Outcome.Captured(transaction, merchant?.name, category?.name)
}

/** Who you split with most recently, for the capture notification's "½ Sam". */
suspend fun TallyDatabase.recentSplitPerson(): String? = recentPeople(splitDao().getOwed(), 1).firstOrNull()

private fun recentPeople(owed: List<SplitEntity>, limit: Int): List<String> =
    People.recent(owed.filter { !it.deleted }.sortedByDescending { it.updatedAt }.mapNotNull { it.owedBy }, limit)
