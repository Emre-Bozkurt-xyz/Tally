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

    @Query("SELECT * FROM transactions WHERE NOT deleted ORDER BY occurredAt DESC")
    fun observeActive(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE NOT deleted AND occurredAt >= :since")
    suspend fun getActiveSince(since: Long): List<TransactionEntity>

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
