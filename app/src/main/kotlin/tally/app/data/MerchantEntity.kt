package tally.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A normalized merchant, plus the category memory used to categorize its next capture. */
@Entity(tableName = "merchants", indices = [Index("name")])
data class MerchantEntity(
    @PrimaryKey val id: String,
    /** Normalized display name. */
    val name: String,
    val lastCategoryId: String?,
    val confirmCount: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Maps raw merchant strings matching [pattern] (a regex) to [merchantName]. */
@Entity(tableName = "aliases")
data class AliasEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val merchantName: String,
    /** Lower is checked first; the first matching alias wins. (v4) */
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "rules", indices = [Index("priority")])
data class RuleEntity(
    @PrimaryKey val id: String,
    /** Lower runs first; the first matching rule wins. */
    val priority: Int,
    /** Regex matched against the normalized merchant name. */
    val merchantPattern: String,
    val minAmountCents: Long?,
    val maxAmountCents: Long?,
    val accountId: String?,
    val categoryId: String,
    val tagsCsv: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
