package tally.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantDao {
    @Upsert
    suspend fun upsert(merchant: MerchantEntity)

    @Query("SELECT * FROM merchants WHERE id = :id")
    suspend fun getById(id: String): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE name = :name COLLATE NOCASE AND NOT deleted LIMIT 1")
    suspend fun findByName(name: String): MerchantEntity?

    /** Includes deleted merchants, so old transactions keep their name. */
    @Query("SELECT * FROM merchants")
    fun observeAll(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchants")
    suspend fun getAll(): List<MerchantEntity>
}

@Dao
interface AccountIdentifierDao {
    @Insert
    suspend fun insert(identifier: AccountIdentifierEntity)

    @Query("SELECT accountId FROM account_identifiers WHERE last4 = :last4 AND NOT deleted LIMIT 1")
    suspend fun accountFor(last4: String): String?
}
