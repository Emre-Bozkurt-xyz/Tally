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
)

/** Null if the stored pattern is invalid (it can't be saved that way, but stay safe). */
fun AliasEntity.toAlias(): Alias? = runCatching { Alias(Regex(pattern), merchantName) }.getOrNull()
