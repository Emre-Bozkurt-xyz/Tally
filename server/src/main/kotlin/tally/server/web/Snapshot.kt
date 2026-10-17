package tally.server.web

import kotlinx.serialization.json.JsonObject
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.report.LedgerEntry
import tally.core.report.SplitPart
import tally.core.report.withSplits
import tally.core.sync.SyncServer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the dashboard shows, read from the synced rows. Loaded per request: one person's
 * ledger is a few thousand rows at most, and this way the page is never stale after a sync.
 */
class Snapshot(
    transactions: List<TransactionRow>,
    splits: List<SplitRow>,
    tags: List<TagRow>,
    links: List<TransactionTagRow>,
    categories: List<CategoryRow>,
    accounts: List<AccountRow>,
    merchants: List<MerchantRow>,
    val aliases: List<AliasRow>,
    val rules: List<RuleRow>,
    /** The stored JSON per table and id, so edits keep columns this server doesn't know. */
    private val stored: Map<String, Map<String, JsonObject>> = emptyMap(),
) {
    /** Live transactions, newest first. */
    val transactions = transactions.filter { !it.deleted }.sortedByDescending { it.occurredAt }
    val splits = splits.filter { !it.deleted }.groupBy { it.transactionId }

    /** Including deleted ones, so old transactions keep their labels. */
    val categoriesById = categories.associateBy { it.id }
    val accountsById = accounts.associateBy { it.id }
    val merchantsById = merchants.associateBy { it.id }
    val tagsById = tags.associateBy { it.id }

    /** Live categories, parents by sort order, each followed by its children. */
    val categoryTree: List<CategoryRow> = categories.filter { !it.deleted }.let { live ->
        live.filter { it.parentId == null }.sortedBy { it.sortOrder }.flatMap { parent ->
            listOf(parent) + live.filter { it.parentId == parent.id }.sortedBy { it.sortOrder }
        }
    }
    val accounts = accounts.filter { !it.deleted }.sortedBy { it.name }
    val tags = tags.filter { !it.deleted }.sortedBy { it.name.lowercase() }
    val tagsByTransaction: Map<String, List<TagRow>> = links.filter { !it.deleted }
        .groupBy({ it.transactionId }) { tagsById[it.tagId] }
        .mapValues { (_, list) -> list.filterNotNull().filter { !it.deleted } }

    fun stored(table: String, id: String): JsonObject? = stored[table]?.get(id)

    fun parentOf(categoryId: String): String? = categoriesById[categoryId]?.parentId

    /** "Food/Coffee" (what rules and TOML use). */
    fun path(categoryId: String?): String? {
        val category = categoryId?.let { categoriesById[it] } ?: return null
        return category.parentId?.let { "${categoriesById[it]?.name}/${category.name}" } ?: category.name
    }

    /** "Food › Coffee" for display. */
    fun label(categoryId: String?): String = path(categoryId)?.replace("/", " › ") ?: "Uncategorized"

    fun merchantName(t: TransactionRow): String =
        t.merchantId?.let { merchantsById[it]?.name } ?: t.merchantRaw ?: t.note ?: "Manual entry"

    fun date(t: TransactionRow): LocalDate = Instant.ofEpochMilli(t.occurredAt).atZone(zoneOf(t)).toLocalDate()

    /** Captures waiting for you, newest first. */
    val needsReview = this.transactions.filter { it.status == TransactionStatus.CAPTURED.name }

    /** What reports count: each transaction, expanded by its splits (owed parts don't count). */
    val entries: List<LedgerEntry> = this.transactions.flatMap { t ->
        val kind = runCatching { Kind.valueOf(t.kind) }.getOrDefault(Kind.EXPENSE)
        LedgerEntry(t.amountCents, kind, t.categoryId, date(t), t.merchantId)
            .withSplits(this.splits[t.id].orEmpty().map { SplitPart(it.amountCents, it.categoryId, it.owedBy) })
    }

    companion object {
        fun load(sync: SyncServer): Snapshot {
            val raw = mutableMapOf<String, Map<String, JsonObject>>()
            fun <T> read(type: RowType<T>): List<T> {
                val changes = sync.current(type.table)
                raw[type.table] = changes.associate { it.id to it.data }
                return changes.mapNotNull { type.decode(it) }
            }
            return Snapshot(
                transactions = read(Rows.transactions),
                splits = read(Rows.splits),
                tags = read(Rows.tags),
                links = read(Rows.transactionTags),
                categories = read(Rows.categories),
                accounts = read(Rows.accounts),
                merchants = read(Rows.merchants),
                aliases = read(Rows.aliases),
                rules = read(Rows.rules),
                stored = raw,
            )
        }

        private fun zoneOf(t: TransactionRow): ZoneId = runCatching { ZoneId.of(t.zoneId) }.getOrDefault(ZoneId.of("America/Toronto"))
    }
}
