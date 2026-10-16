package tally.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE NOT deleted ORDER BY sortOrder")
    fun observeActive(): Flow<List<CategoryEntity>>

    /** Includes soft-deleted rows, so deleting every category never triggers a re-seed. */
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int
}

@Dao
interface AccountDao {
    @Insert
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE NOT deleted ORDER BY name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int
}
