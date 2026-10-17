package tally.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tally.core.normalize.Alias
import tally.core.rules.Rule

@Dao
interface RuleDao {
    @Upsert
    suspend fun upsertAll(rules: List<RuleEntity>)

    @Query("SELECT * FROM rules WHERE NOT deleted ORDER BY priority")
    suspend fun getActive(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE NOT deleted ORDER BY priority")
    fun observeActive(): Flow<List<RuleEntity>>
}

@Dao
interface AliasDao {
    @Upsert
    suspend fun upsertAll(aliases: List<AliasEntity>)

    @Query("SELECT * FROM aliases WHERE NOT deleted ORDER BY priority")
    suspend fun getActive(): List<AliasEntity>

    @Query("SELECT * FROM aliases WHERE NOT deleted ORDER BY priority")
    fun observeActive(): Flow<List<AliasEntity>>
}

@Dao
interface TagDao {
    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE AND NOT deleted LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Upsert
    suspend fun link(crossRef: TransactionTagCrossRef)

    @Query("UPDATE transaction_tags SET deleted = 1, updatedAt = :now WHERE transactionId = :transactionId AND tagId = :tagId")
    suspend fun unlink(transactionId: String, tagId: String, now: Long)

    @Query("SELECT * FROM tags WHERE NOT deleted ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<TagEntity>>

    /** Every live transaction-tag link, for filtering the ledger. */
    @Query("SELECT * FROM transaction_tags WHERE NOT deleted")
    fun observeLinks(): Flow<List<TransactionTagCrossRef>>

    @Query(
        """
        SELECT t.* FROM tags t JOIN transaction_tags x ON x.tagId = t.id
        WHERE x.transactionId = :transactionId AND NOT x.deleted AND NOT t.deleted
        ORDER BY t.name COLLATE NOCASE
        """,
    )
    fun observeFor(transactionId: String): Flow<List<TagEntity>>
}

@Dao
interface SplitDao {
    @Upsert
    suspend fun upsert(split: SplitEntity)

    @Query("SELECT * FROM splits WHERE transactionId = :transactionId AND NOT deleted")
    suspend fun getFor(transactionId: String): List<SplitEntity>

    @Query("SELECT * FROM splits WHERE transactionId = :transactionId AND NOT deleted")
    fun observeFor(transactionId: String): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits WHERE NOT deleted")
    fun observeAll(): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits WHERE NOT deleted")
    suspend fun getAll(): List<SplitEntity>

    /** Portions someone owes you, settled or not. */
    @Query("SELECT * FROM splits WHERE owedBy IS NOT NULL AND NOT deleted")
    fun observeOwed(): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits WHERE id = :id")
    suspend fun getById(id: String): SplitEntity?

    @Query("SELECT * FROM splits WHERE owedBy IS NOT NULL AND NOT deleted ORDER BY updatedAt")
    suspend fun getOwed(): List<SplitEntity>

    @Query("UPDATE splits SET settledAt = :settledAt, updatedAt = :now WHERE id = :id")
    suspend fun setSettled(id: String, settledAt: Long?, now: Long)

    @Query("UPDATE splits SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long)
}

fun RuleEntity.toRule() = Rule(
    id = id,
    priority = priority,
    merchantPattern = merchantPattern,
    minAmountCents = minAmountCents,
    maxAmountCents = maxAmountCents,
    accountId = accountId,
    categoryId = categoryId,
    tags = tagsCsv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
    ask = ask,
)

/** Null if the stored pattern is invalid (it can't be saved that way, but stay safe). */
fun AliasEntity.toAlias(): Alias? = runCatching { Alias(Regex(pattern), merchantName) }.getOrNull()
