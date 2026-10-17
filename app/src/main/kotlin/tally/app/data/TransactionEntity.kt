package tally.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Syncable tables share two conventions: UUID string ids generated on the device, and
// `updatedAt` (epoch ms) + `deleted` (soft delete) for last-write-wins sync. No foreign key
// constraints: sync can deliver rows in any order, so references are plain indexed columns.

@Entity(
    tableName = "transactions",
    indices = [Index("occurredAt"), Index("categoryId"), Index("merchantId"), Index("accountId")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    /** Positive = money out, negative = money in (refunds, deposits). */
    val amountCents: Long,
    val currency: String,
    /** Epoch ms. */
    val occurredAt: Long,
    val zoneId: String,
    val accountId: String?,
    val merchantId: String?,
    /** As captured, before normalization. */
    val merchantRaw: String?,
    val categoryId: String?,
    val note: String?,
    /** A [tally.core.model.Source] name. */
    val source: String,
    /** A [tally.core.model.TransactionStatus] name. */
    val status: String,
    /** A [tally.core.model.CategorySource] name. */
    val categorySource: String?,
    /** A [tally.core.model.Kind] name. */
    val kind: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Part of a transaction with its own category, or a portion someone else owes. */
@Entity(tableName = "splits", indices = [Index("transactionId")])
data class SplitEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val amountCents: Long,
    val categoryId: String?,
    /** Free-text name of whoever owes this portion; null for a plain category split. */
    val owedBy: String?,
    val updatedAt: Long,
    /** When an owed portion was paid back (epoch ms); null while outstanding. (v5) */
    val settledAt: Long? = null,
    val deleted: Boolean = false,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transactionId", "tagId"],
    indices = [Index("tagId")],
)
data class TransactionTagCrossRef(
    val transactionId: String,
    val tagId: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
