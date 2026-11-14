package tally.server.web

import kotlinx.serialization.json.JsonObject
import tally.core.model.CategoryKinds
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.report.Counting
import tally.core.report.LedgerEntry
import tally.core.report.Balance
import tally.core.report.Balances
import tally.core.report.Owed
import tally.core.report.People
import tally.core.report.Repayment
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
    identifiers: List<AccountIdentifierRow> = emptyList(),
    merchants: List<MerchantRow>,
    val aliases: List<AliasRow>,
    val rules: List<RuleRow>,
    settlements: List<SettlementRow> = emptyList(),
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
    /** Last-4 to account, learned on the phone from captures. */
    val accountByLast4: Map<String, String> = identifiers.filter { !it.deleted }.associate { it.last4 to it.accountId }
    val tags = tags.filter { !it.deleted }.sortedBy { it.name.lowercase() }
    val tagsByTransaction: Map<String, List<TagRow>> = links.filter { !it.deleted }
        .groupBy({ it.transactionId }) { tagsById[it.tagId] }
        .mapValues { (_, list) -> list.filterNotNull().filter { !it.deleted } }

    fun stored(table: String, id: String): JsonObject? = stored[table]?.get(id)

    fun parentOf(categoryId: String): String? = categoriesById[categoryId]?.parentId

    /** What counts toward the everyday number: every category but the ones switched off (rent, bills). */
    val counting = Counting(categories.filter { !it.counted }.map { it.id }.toSet(), ::parentOf)

    /** "Food/Coffee" (what rules and TOML use). */
    fun path(categoryId: String?): String? {
        val category = categoryId?.let { categoriesById[it] } ?: return null
        return category.parentId?.let { "${categoriesById[it]?.name}/${category.name}" } ?: category.name
    }

    /** "Food › Coffee" for display. */
    fun label(categoryId: String?): String = path(categoryId)?.replace("/", " › ") ?: "Uncategorized"

    /** The merchant, or what the bank said it was ("Withdrawal", "Deposit"), as on the phone. */
    fun merchantName(t: TransactionRow): String =
        t.merchantId?.let { merchantsById[it]?.name } ?: t.merchantRaw ?: t.note ?: when {
            t.amountCents < 0 -> "Deposit"
            t.source == "RBC_ALERT" -> "Withdrawal"
            t.source == "MANUAL" -> "Manual entry"
            else -> "Card purchase"
        }

    fun date(t: TransactionRow): LocalDate = Instant.ofEpochMilli(t.occurredAt).atZone(zoneOf(t)).toLocalDate()

    /** Who owes you what: owed parts of live purchases minus what they paid back, one balance per person. */
    val balances: List<Balance> = this.transactions.associateBy { it.id }.let { live ->
        Balances.of(
            splits.filter { !it.deleted && it.owedBy != null }.mapNotNull { split ->
                val t = live[split.transactionId] ?: return@mapNotNull null
                Owed(split.id, split.owedBy!!, split.amountCents, t.occurredAt, t.categoryId, t.id, settledBefore = split.settledAt != null)
            },
            settlements.filter { !it.deleted }.map { Repayment(it.id, it.person, it.amountCents, it.differenceCents, it.occurredAt, it.transactionId) },
        )
    }

    /** People who owe you something, most first. */
    val owing: List<Balance> = balances.filter { it.balanceCents > 0 }

    /** Money in that isn't a transfer, and whose sender (or the deposit alert) names no one. */
    fun isDeposit(t: TransactionRow) = t.amountCents < 0 && t.kind != Kind.TRANSFER.name

    fun senderOf(t: TransactionRow): String? = t.merchantId?.let { merchantsById[it]?.name } ?: t.merchantRaw

    /** Who money in from [t] pays back: its sender if they owe you, or [picked] for a deposit with no name. */
    fun payer(t: TransactionRow, picked: String? = null): Balance? {
        if (!isDeposit(t)) return null
        return if (picked != null) owing.firstOrNull { People.same(it.person, picked) } else Balances.forSender(senderOf(t), owing)
    }

    /** Under the top-level "Income" category (money in there is income, elsewhere it's money back). */
    fun isIncomeCategory(categoryId: String?): Boolean {
        val category = categoryId?.let { categoriesById[it] } ?: return false
        return CategoryKinds.isIncome((category.parentId?.let { categoriesById[it] } ?: category).name)
    }

    /** Captures waiting for you, newest first. */
    val needsReview = this.transactions.filter { it.status == TransactionStatus.CAPTURED.name }

    /** What reports count: each transaction, expanded by its splits (owed parts don't count). */
    val entries: List<LedgerEntry> = this.transactions.flatMap { t ->
        val kind = runCatching { Kind.valueOf(t.kind) }.getOrDefault(Kind.EXPENSE)
        LedgerEntry(t.amountCents, kind, t.categoryId, date(t), t.merchantId, t.counted)
            .withSplits(this.splits[t.id].orEmpty().map { SplitPart(it.amountCents, it.categoryId, it.owedBy) })
    } + Balances.differenceEntries(balances, HOME_ZONE)

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
                identifiers = read(Rows.accountIdentifiers),
                merchants = read(Rows.merchants),
                aliases = read(Rows.aliases),
                rules = read(Rows.rules),
                settlements = read(Rows.settlements),
                stored = raw,
            )
        }

        /** Where dates without a transaction of their own (a settle-up) fall. */
        private val HOME_ZONE: ZoneId = ZoneId.of("America/Toronto")

        private fun zoneOf(t: TransactionRow): ZoneId = runCatching { ZoneId.of(t.zoneId) }.getOrDefault(HOME_ZONE)
    }
}
