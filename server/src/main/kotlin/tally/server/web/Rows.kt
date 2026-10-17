package tally.server.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tally.core.sync.SyncChange

// Mirrors of the phone's Room entities (app/src/main/kotlin/tally/app/data), field for field:
// the dashboard reads the synced JSON with these, and its edits must decode on the phone.
// `RowsSchemaTest` checks them against the committed Room schema.

@Serializable
data class TransactionRow(
    val id: String,
    val amountCents: Long,
    val currency: String,
    val occurredAt: Long,
    val zoneId: String,
    val accountId: String?,
    val merchantId: String?,
    val merchantRaw: String?,
    val categoryId: String?,
    val note: String?,
    val source: String,
    val status: String,
    val categorySource: String?,
    val kind: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SplitRow(
    val id: String,
    val transactionId: String,
    val amountCents: Long,
    val categoryId: String?,
    val owedBy: String?,
    val updatedAt: Long,
    val settledAt: Long? = null,
    val deleted: Boolean = false,
)

@Serializable
data class TagRow(val id: String, val name: String, val updatedAt: Long, val deleted: Boolean = false)

@Serializable
data class TransactionTagRow(val transactionId: String, val tagId: String, val updatedAt: Long, val deleted: Boolean = false)

@Serializable
data class CategoryRow(
    val id: String,
    val name: String,
    val parentId: String?,
    val monthlyBudgetCents: Long?,
    val sortOrder: Int,
    val pinned: Boolean,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class AccountRow(
    val id: String,
    val name: String,
    val last4: String?,
    val walletSource: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class AccountIdentifierRow(val id: String, val accountId: String, val last4: String, val updatedAt: Long, val deleted: Boolean = false)

@Serializable
data class MerchantRow(
    val id: String,
    val name: String,
    val lastCategoryId: String?,
    val confirmCount: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class AliasRow(
    val id: String,
    val pattern: String,
    val merchantName: String,
    val priority: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class RuleRow(
    val id: String,
    val priority: Int,
    val merchantPattern: String,
    val minAmountCents: Long?,
    val maxAmountCents: Long?,
    val accountId: String?,
    val categoryId: String,
    val tagsCsv: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val ask: Boolean = false,
)

/** A synced table the dashboard reads and writes: its name and how its rows look. */
class RowType<T>(
    val table: String,
    val serializer: KSerializer<T>,
    val id: (T) -> String,
    val updatedAt: (T) -> Long,
    val deleted: (T) -> Boolean,
) {
    fun decode(change: SyncChange): T? = runCatching { rowJson.decodeFromJsonElement(serializer, change.data) }.getOrNull()

    /**
     * The row as a [SyncChange]. Written over the stored JSON, so columns a newer phone has
     * and this server doesn't know about survive the edit.
     */
    fun encode(row: T, stored: JsonObject?): SyncChange {
        val fields = rowJson.encodeToJsonElement(serializer, row).jsonObject
        return SyncChange(table, id(row), updatedAt(row), deleted(row), JsonObject((stored ?: emptyMap()) + fields))
    }
}

object Rows {
    val transactions = RowType("transactions", TransactionRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val splits = RowType("splits", SplitRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val tags = RowType("tags", TagRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val transactionTags = RowType(
        "transaction_tags", TransactionTagRow.serializer(), { "${it.transactionId}|${it.tagId}" }, { it.updatedAt }, { it.deleted },
    )
    val categories = RowType("categories", CategoryRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val accounts = RowType("accounts", AccountRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val accountIdentifiers = RowType("account_identifiers", AccountIdentifierRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val merchants = RowType("merchants", MerchantRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val aliases = RowType("aliases", AliasRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
    val rules = RowType("rules", RuleRow.serializer(), { it.id }, { it.updatedAt }, { it.deleted })
}

private val rowJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
