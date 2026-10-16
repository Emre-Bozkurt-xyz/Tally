package tally.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id AND NOT deleted")
    suspend fun getById(id: String): TransactionEntity?

    @Query(
        """
        SELECT categoryId, occurredAt, zoneId FROM transactions
        WHERE NOT deleted AND categoryId IS NOT NULL AND occurredAt >= :since
        """,
    )
    suspend fun categoryUsesSince(since: Long): List<CategoryUseRow>
}

data class CategoryUseRow(val categoryId: String, val occurredAt: Long, val zoneId: String)
