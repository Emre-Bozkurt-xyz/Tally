package tally.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Two levels: top-level categories (`parentId == null`) and their subcategories. A
 * transaction can use either; parent totals include their subcategories.
 */
@Entity(tableName = "categories", indices = [Index("parentId")])
@Serializable
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val monthlyBudgetCents: Long?,
    val sortOrder: Int,
    /** Shown on the widget and first in the quick-entry chips. */
    val pinned: Boolean,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "accounts")
@Serializable
data class AccountEntity(
    @PrimaryKey val id: String,
    /** "RBC Visa", "RBC Chequing", "Cash". */
    val name: String,
    val last4: String?,
    /** The wallet [tally.core.model.Source] name whose captures belong to this account. */
    val walletSource: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * A last-4 that identifies an account. One account has several: a debit purchase shows the
 * card's last 4 in the wallet and the bank account's last 4 in the RBC alert. Learned when
 * you set the account on a captured transaction.
 */
@Entity(tableName = "account_identifiers", indices = [Index("last4"), Index("accountId")])
@Serializable
data class AccountIdentifierEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val last4: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
