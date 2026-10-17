package tally.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Per-table reads and writes for sync ([tally.app.sync.syncTables]): rows changed since a
 * time, one row's `updatedAt` (to decide whether an incoming copy is newer), and upserts.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM transactions WHERE updatedAt >= :since")
    suspend fun transactionsSince(since: Long): List<TransactionEntity>

    @Query("SELECT updatedAt FROM transactions WHERE id = :id")
    suspend fun transactionUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertTransactions(rows: List<TransactionEntity>)

    @Query("SELECT * FROM splits WHERE updatedAt >= :since")
    suspend fun splitsSince(since: Long): List<SplitEntity>

    @Query("SELECT updatedAt FROM splits WHERE id = :id")
    suspend fun splitUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertSplits(rows: List<SplitEntity>)

    @Query("SELECT * FROM tags WHERE updatedAt >= :since")
    suspend fun tagsSince(since: Long): List<TagEntity>

    @Query("SELECT updatedAt FROM tags WHERE id = :id")
    suspend fun tagUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertTags(rows: List<TagEntity>)

    @Query("SELECT * FROM transaction_tags WHERE updatedAt >= :since")
    suspend fun transactionTagsSince(since: Long): List<TransactionTagCrossRef>

    @Query("SELECT updatedAt FROM transaction_tags WHERE transactionId = :transactionId AND tagId = :tagId")
    suspend fun transactionTagUpdatedAt(transactionId: String, tagId: String): Long?

    @Upsert
    suspend fun upsertTransactionTags(rows: List<TransactionTagCrossRef>)

    @Query("SELECT * FROM categories WHERE updatedAt >= :since")
    suspend fun categoriesSince(since: Long): List<CategoryEntity>

    @Query("SELECT updatedAt FROM categories WHERE id = :id")
    suspend fun categoryUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertCategories(rows: List<CategoryEntity>)

    @Query("SELECT * FROM accounts WHERE updatedAt >= :since")
    suspend fun accountsSince(since: Long): List<AccountEntity>

    @Query("SELECT updatedAt FROM accounts WHERE id = :id")
    suspend fun accountUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertAccounts(rows: List<AccountEntity>)

    @Query("SELECT * FROM account_identifiers WHERE updatedAt >= :since")
    suspend fun accountIdentifiersSince(since: Long): List<AccountIdentifierEntity>

    @Query("SELECT updatedAt FROM account_identifiers WHERE id = :id")
    suspend fun accountIdentifierUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertAccountIdentifiers(rows: List<AccountIdentifierEntity>)

    @Query("SELECT * FROM merchants WHERE updatedAt >= :since")
    suspend fun merchantsSince(since: Long): List<MerchantEntity>

    @Query("SELECT updatedAt FROM merchants WHERE id = :id")
    suspend fun merchantUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertMerchants(rows: List<MerchantEntity>)

    @Query("SELECT * FROM aliases WHERE updatedAt >= :since")
    suspend fun aliasesSince(since: Long): List<AliasEntity>

    @Query("SELECT updatedAt FROM aliases WHERE id = :id")
    suspend fun aliasUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertAliases(rows: List<AliasEntity>)

    @Query("SELECT * FROM rules WHERE updatedAt >= :since")
    suspend fun rulesSince(since: Long): List<RuleEntity>

    @Query("SELECT updatedAt FROM rules WHERE id = :id")
    suspend fun ruleUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertRules(rows: List<RuleEntity>)

    /** Only bank and wallet notifications leave the phone, never the "log all packages" ones. */
    @Query("SELECT * FROM raw_events WHERE updatedAt >= :since AND source IN (:sources)")
    suspend fun rawEventsSince(since: Long, sources: Set<String>): List<RawEventEntity>

    @Query("SELECT updatedAt FROM raw_events WHERE id = :id")
    suspend fun rawEventUpdatedAt(id: String): Long?

    @Upsert
    suspend fun upsertRawEvents(rows: List<RawEventEntity>)
}
