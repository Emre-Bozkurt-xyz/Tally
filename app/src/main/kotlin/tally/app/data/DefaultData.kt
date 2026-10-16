package tally.app.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import java.util.UUID

/**
 * Starter accounts and categories, inserted on first run.
 *
 * Ids are derived from the name, and `updatedAt` is 0, so after a reinstall the seeded rows
 * line up with the synced copies and any real edit wins over the defaults (Phase 4).
 */
object DefaultData {
    const val CASH_ACCOUNT_NAME = "Cash"

    private val accountNames = listOf("RBC Visa", "RBC Chequing", CASH_ACCOUNT_NAME)

    /** Top-level category to subcategories, in display order. */
    private val categoryTree = listOf(
        "Food" to listOf("Groceries", "Dining out", "Delivery", "Coffee", "Convenience"),
        "Shopping" to listOf("Online", "In store"),
        "Transport" to listOf("Transit", "Rideshare"),
        "Bills" to listOf("Phone", "Subscriptions"),
        "Entertainment" to emptyList(),
        "Health" to emptyList(),
    )

    /** Shown on the widget and first in the quick-entry chips. */
    private val pinnedPaths = setOf("Food/Coffee", "Food/Convenience", "Food/Dining out", "Food/Groceries")

    fun accountId(name: String): String = stableId("account:$name")

    /** [path] is "Parent" or "Parent/Child", the same form rules and TOML use. */
    fun categoryId(path: String): String = stableId("category:$path")

    fun accounts(): List<AccountEntity> = accountNames.map { name ->
        AccountEntity(id = accountId(name), name = name, last4 = null, walletSource = null, updatedAt = 0)
    }

    fun categories(): List<CategoryEntity> = categoryTree.flatMapIndexed { parentIndex, (parent, children) ->
        val parentEntity = category(path = parent, name = parent, parentId = null, sortOrder = parentIndex)
        listOf(parentEntity) + children.mapIndexed { childIndex, child ->
            category(path = "$parent/$child", name = child, parentId = parentEntity.id, sortOrder = childIndex)
        }
    }

    /** Inserts the defaults into empty tables. Safe to call on every start. */
    suspend fun seedIfEmpty(db: TallyDatabase) = db.useWriterConnection { connection ->
        connection.immediateTransaction {
            if (db.accountDao().countAll() == 0) db.accountDao().insertAll(accounts())
            if (db.categoryDao().countAll() == 0) db.categoryDao().insertAll(categories())
        }
    }

    private fun category(path: String, name: String, parentId: String?, sortOrder: Int) = CategoryEntity(
        id = categoryId(path),
        name = name,
        parentId = parentId,
        monthlyBudgetCents = null,
        sortOrder = sortOrder,
        pinned = path in pinnedPaths,
        updatedAt = 0,
    )

    private fun stableId(key: String): String = UUID.nameUUIDFromBytes("tally:$key".toByteArray()).toString()
}
