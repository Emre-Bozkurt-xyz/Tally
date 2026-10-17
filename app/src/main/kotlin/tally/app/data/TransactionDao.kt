package tally.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id AND NOT deleted")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): TransactionEntity?

    /**
     * Captured (non-manual) transactions near a time, with the packages of the notifications
     * already attached. Includes deleted ones, so the second notification of an ignored
     * purchase is absorbed instead of creating it again.
     */
    @Query(
        """
        SELECT t.id AS transactionId, t.amountCents, t.occurredAt, t.accountId,
               GROUP_CONCAT(r.source) AS rawSources
        FROM transactions t LEFT JOIN raw_events r ON r.transactionId = t.id
        WHERE t.source != 'MANUAL' AND t.occurredAt BETWEEN :from AND :to
        GROUP BY t.id
        """,
    )
    suspend fun dedupeCandidates(from: Long, to: Long): List<DedupeRow>

    @Query("SELECT * FROM transactions WHERE NOT deleted ORDER BY occurredAt DESC")
    fun observeActive(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE NOT deleted AND occurredAt >= :since")
    suspend fun getActiveSince(since: Long): List<TransactionEntity>

    /** Gives a merchant's past transactions (up to an amount) a category from a new rule. */
    @Query(
        """
        UPDATE transactions
        SET categoryId = :categoryId, categorySource = 'RULE', status = 'CONFIRMED', updatedAt = :now
        WHERE merchantId = :merchantId AND NOT deleted AND amountCents <= :maxAmountCents
        """,
    )
    suspend fun categorizeMerchant(merchantId: String, categoryId: String, maxAmountCents: Long, now: Long): Int

    /** Soft delete (and undo), so the change syncs. */
    @Query("UPDATE transactions SET deleted = :deleted, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDeleted(id: String, deleted: Boolean, updatedAt: Long)

    @Query(
        """
        SELECT categoryId, occurredAt, zoneId FROM transactions
        WHERE NOT deleted AND categoryId IS NOT NULL AND occurredAt >= :since
        """,
    )
    suspend fun categoryUsesSince(since: Long): List<CategoryUseRow>
}

data class CategoryUseRow(val categoryId: String, val occurredAt: Long, val zoneId: String)

data class DedupeRow(
    val transactionId: String,
    val amountCents: Long,
    val occurredAt: Long,
    val accountId: String?,
    /** Comma-separated package names, or null when none are attached. */
    val rawSources: String?,
)
