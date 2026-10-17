package tally.app.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tally.app.data.AccountEntity
import tally.app.data.AccountIdentifierEntity
import tally.app.data.AliasEntity
import tally.app.data.CategoryEntity
import tally.app.data.MerchantEntity
import tally.app.data.RawEventEntity
import tally.app.data.RuleEntity
import tally.app.data.SplitEntity
import tally.app.data.SyncDao
import tally.app.data.TagEntity
import tally.app.data.TransactionEntity
import tally.app.data.TransactionTagCrossRef
import tally.core.capture.WatchedPackages
import tally.core.sync.SyncChange
import tally.core.sync.SyncProtocol

/**
 * How one Room table travels: each row becomes a [SyncChange] whose `data` is the entity as
 * JSON, keyed by [key] (the primary key; `transactionId|tagId` for the tag cross-ref).
 */
class SyncTable<T>(
    val name: String,
    private val serializer: KSerializer<T>,
    private val key: (T) -> String,
    private val updatedAt: (T) -> Long,
    private val deleted: (T) -> Boolean,
    private val since: suspend (Long) -> List<T>,
    /** The local row's `updatedAt`, or null if there's no such row. */
    val localUpdatedAt: suspend (key: String) -> Long?,
    private val upsert: suspend (List<T>) -> Unit,
) {
    suspend fun changedSince(time: Long): List<SyncChange> = since(time).map { row ->
        SyncChange(name, key(row), updatedAt(row), deleted(row), json.encodeToJsonElement(serializer, row).jsonObject)
    }

    suspend fun apply(changes: List<SyncChange>) {
        if (changes.isNotEmpty()) upsert(changes.map { json.decodeFromJsonElement(serializer, it.data) })
    }

    private companion object {
        // Defaults included so the server's JSON always has every column (the dashboard reads
        // it); unknown keys ignored so an older app can still read rows from a newer one.
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** Every synced table. Must cover [SyncProtocol.TABLES] exactly. */
fun syncTables(dao: SyncDao): List<SyncTable<*>> = listOf(
    SyncTable(
        "transactions", TransactionEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::transactionsSince, dao::transactionUpdatedAt, dao::upsertTransactions,
    ),
    SyncTable(
        "splits", SplitEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::splitsSince, dao::splitUpdatedAt, dao::upsertSplits,
    ),
    SyncTable(
        "tags", TagEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::tagsSince, dao::tagUpdatedAt, dao::upsertTags,
    ),
    SyncTable(
        "transaction_tags", TransactionTagCrossRef.serializer(),
        { "${it.transactionId}|${it.tagId}" }, { it.updatedAt }, { it.deleted },
        dao::transactionTagsSince,
        { key ->
            val parts = key.split('|', limit = 2)
            if (parts.size == 2) dao.transactionTagUpdatedAt(parts[0], parts[1]) else null
        },
        dao::upsertTransactionTags,
    ),
    SyncTable(
        "categories", CategoryEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::categoriesSince, dao::categoryUpdatedAt, dao::upsertCategories,
    ),
    SyncTable(
        "accounts", AccountEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::accountsSince, dao::accountUpdatedAt, dao::upsertAccounts,
    ),
    SyncTable(
        "account_identifiers", AccountIdentifierEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::accountIdentifiersSince, dao::accountIdentifierUpdatedAt, dao::upsertAccountIdentifiers,
    ),
    SyncTable(
        "merchants", MerchantEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::merchantsSince, dao::merchantUpdatedAt, dao::upsertMerchants,
    ),
    SyncTable(
        "aliases", AliasEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::aliasesSince, dao::aliasUpdatedAt, dao::upsertAliases,
    ),
    SyncTable(
        "rules", RuleEntity.serializer(), { it.id }, { it.updatedAt }, { it.deleted },
        dao::rulesSince, dao::ruleUpdatedAt, dao::upsertRules,
    ),
    // Raw events are kept, never deleted: they're the source data parsers can re-run over.
    // Only bank and wallet ones sync, never notifications logged with "log all packages".
    SyncTable(
        "raw_events", RawEventEntity.serializer(), { it.id }, { it.updatedAt }, { false },
        { dao.rawEventsSince(it, WatchedPackages.ALL) }, dao::rawEventUpdatedAt, dao::upsertRawEvents,
    ),
).also { tables ->
    check(tables.map { it.name }.toSet() == SyncProtocol.TABLES) { "syncTables out of step with SyncProtocol.TABLES" }
}
