package tally.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Syncable tables share two conventions: UUID string ids generated on the device, and
// `updatedAt` (epoch ms) + `deleted` (soft delete) for last-write-wins sync. No foreign key
// constraints: sync can deliver rows in any order, so references are plain indexed columns.
// @Serializable: rows travel to the server as JSON (tally.app.sync.SyncTables).

@Entity(
    tableName = "transactions",
    indices = [Index("occurredAt"), Index("categoryId"), Index("merchantId"), Index("accountId")],
)
@Serializable
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
    /** Counts toward the everyday number: null follows the category, true or false overrides it. (v8) */
    val counted: Boolean? = null,
)

/** Part of a transaction with its own category, or a portion someone else owes. */
@Entity(tableName = "splits", indices = [Index("transactionId")])
@Serializable
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

/**
 * Money a person paid back, against their balance (owed parts minus settlements) rather than
 * one item, so one e-Transfer can cover several things. (v8)
 */
@Entity(tableName = "settlements", indices = [Index("person"), Index("transactionId")])
@Serializable
data class SettlementEntity(
    @PrimaryKey val id: String,
    /** As written on the owed parts ("Sam"); matched by first or full name. */
    val person: String,
    /** What they paid. */
    val amountCents: Long,
    /** The deposit (e-Transfer) that paid it; null for cash or anything Tally didn't see. */
    val transactionId: String?,
    /**
     * What "Call it even" absorbed: positive when they paid less than they owed (your
     * spending), negative when more (money back to you). 0 for an exact or partial payment.
     */
    val differenceCents: Long,
    /** Epoch ms. */
    val occurredAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "tags")
@Serializable
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
@Serializable
data class TransactionTagCrossRef(
    val transactionId: String,
    val tagId: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
