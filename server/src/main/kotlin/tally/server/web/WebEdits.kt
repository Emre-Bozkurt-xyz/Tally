package tally.server.web

import tally.core.model.CategoryKinds
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.rules.AliasSpec
import tally.core.rules.CategorySpec
import tally.core.rules.MerchantMemory
import tally.core.rules.RuleSpec
import tally.core.rules.RulesDocument
import tally.core.rules.RulesToml
import tally.core.rules.TomlImport
import tally.core.sync.SyncChange
import tally.core.sync.SyncServer
import java.time.Clock
import java.util.UUID

/**
 * The dashboard's edits. Each one writes newer rows through [SyncServer.write], the same
 * last-write-wins path as a phone's push, so the phone picks them up on its next sync. They
 * mirror what the same action does on the phone (LedgerRepository, RulesRepository).
 */
class WebEdits(private val sync: SyncServer, private val clock: Clock = Clock.systemUTC()) {
    companion object {
        const val WEB = "web"
    }

    fun snapshot() = Snapshot.load(sync)

    /** You picked a category (ledger or inbox): confirmed, manual, and merchant memory learns. */
    fun setCategory(transactionId: String, categoryId: String, source: String = WEB): Boolean {
        val s = snapshot()
        val t = s.transactions.find { it.id == transactionId } ?: return false
        if (s.categoryTree.none { it.id == categoryId }) return false
        val now = clock.millis()
        val writes = mutableListOf(
            Rows.transactions.encode(
                t.copy(
                    categoryId = categoryId,
                    categorySource = CategorySource.MANUAL.name,
                    status = TransactionStatus.CONFIRMED.name,
                    // Money in: income under "Income", otherwise money back for that spending.
                    kind = CategoryKinds.kindFor(Kind.valueOf(t.kind), t.amountCents, s.isIncomeCategory(categoryId)).name,
                    updatedAt = now,
                ),
                s.stored(Rows.transactions.table, t.id),
            ),
        )
        // Count it once per decision, as on the phone: confirming a capture, or a new category.
        if (t.status == TransactionStatus.CAPTURED.name || t.categoryId != categoryId) {
            learn(s, t.merchantId, categoryId, now)?.let { writes += it }
        }
        sync.write(writes, source)
        return true
    }

    /** Accept a capture's suggested category as is. */
    fun confirm(transactionId: String, source: String = WEB): Boolean {
        val s = snapshot()
        val t = s.needsReview.find { it.id == transactionId } ?: return false
        val categoryId = t.categoryId ?: return false
        val now = clock.millis()
        sync.write(
            listOfNotNull(
                Rows.transactions.encode(
                    t.copy(status = TransactionStatus.CONFIRMED.name, updatedAt = now),
                    s.stored(Rows.transactions.table, t.id),
                ),
                learn(s, t.merchantId, categoryId, now),
            ),
            source,
        )
        return true
    }

    /**
     * Money in that pays back an "owed to me" part: the part is settled and the money is a
     * transfer (the owed part never counted as your spending), as on the phone.
     */
    fun settle(transactionId: String, splitId: String, source: String = WEB): Boolean {
        val s = snapshot()
        val t = s.transactions.find { it.id == transactionId } ?: return false
        val split = s.splits.values.flatten().find { it.id == splitId }?.takeIf { it.owedBy != null && it.settledAt == null } ?: return false
        val now = clock.millis()
        sync.write(
            listOf(
                Rows.splits.encode(split.copy(settledAt = t.occurredAt, updatedAt = now), s.stored(Rows.splits.table, split.id)),
                Rows.transactions.encode(
                    t.copy(
                        kind = Kind.TRANSFER.name,
                        status = TransactionStatus.CONFIRMED.name,
                        categoryId = null,
                        categorySource = null,
                        note = t.note ?: "${split.owedBy} paid back",
                        updatedAt = now,
                    ),
                    s.stored(Rows.transactions.table, t.id),
                ),
            ),
            source,
        )
        return true
    }

    /** "Not a purchase": deleted, as the notification's Ignore does. */
    fun ignore(transactionId: String, source: String = WEB): Boolean {
        val s = snapshot()
        val t = s.transactions.find { it.id == transactionId } ?: return false
        sync.write(listOf(Rows.transactions.encode(t.copy(deleted = true, updatedAt = clock.millis()), s.stored(Rows.transactions.table, t.id))), source)
        return true
    }

    private fun learn(s: Snapshot, merchantId: String?, categoryId: String, now: Long): SyncChange? {
        val merchant = merchantId?.let { s.merchantsById[it] }?.takeIf { !it.deleted } ?: return null
        val memory = MerchantMemory(merchant.lastCategoryId, merchant.confirmCount).confirmed(categoryId)
        return Rows.merchants.encode(
            merchant.copy(lastCategoryId = memory.lastCategoryId, confirmCount = memory.confirmCount, updatedAt = now),
            s.stored(Rows.merchants.table, merchant.id),
        )
    }

    /** The categories, aliases and rules as the TOML file the phone's editor shows. */
    fun exportToml(s: Snapshot = snapshot()): String {
        val tree = s.categoryTree
        val document = RulesDocument(
            categories = tree.filter { it.parentId == null }.map { parent ->
                CategorySpec(parent.name, tree.filter { it.parentId == parent.id }.map { it.name })
            },
            aliases = s.aliases.filter { !it.deleted }.sortedBy { it.priority }.map { AliasSpec(it.pattern, it.merchantName) },
            rules = s.rules.filter { !it.deleted }.sortedBy { it.priority }.mapNotNull { rule ->
                RuleSpec(
                    merchant = rule.merchantPattern,
                    category = s.path(rule.categoryId)?.takeIf { s.categoriesById[rule.categoryId]?.deleted == false }
                        ?: return@mapNotNull null,
                    minAmountCents = rule.minAmountCents,
                    maxAmountCents = rule.maxAmountCents,
                    account = rule.accountId?.let { s.accountsById[it]?.name },
                    tags = rule.tagsCsv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    ask = rule.ask,
                )
            },
        )
        return RulesToml.export(document)
    }

    /**
     * Validates [text] and, if valid, makes the synced rows match it, as the phone's import
     * does: categories by path (existing ones keep their ids; missing ones are deleted), and
     * aliases and rules in file order, reusing identical rows. The phone re-applies aliases to
     * old transactions when it pulls the change.
     */
    fun importToml(text: String, source: String = WEB): TomlImport {
        val s = snapshot()
        val result = RulesToml.import(text, s.accounts.map { it.name }.toSet())
        if (result !is TomlImport.Ok) return result
        val document = result.document
        val now = clock.millis()
        val writes = mutableListOf<SyncChange>()
        val accountIds = s.accounts.associate { it.name to it.id }

        val existing = s.categoryTree
        val keep = mutableSetOf<String>()
        val pathIds = mutableMapOf<String, String>()
        fun place(name: String, parentId: String?, sortOrder: Int): String {
            val found = existing.find { it.parentId == parentId && it.name == name }
            val wanted = found?.copy(sortOrder = sortOrder)
                ?: CategoryRow(UUID.randomUUID().toString(), name, parentId, null, sortOrder, false, now)
            if (wanted != found) writes += Rows.categories.encode(wanted.copy(updatedAt = now), s.stored(Rows.categories.table, wanted.id))
            keep += wanted.id
            return wanted.id
        }
        document.categories.forEachIndexed { i, spec ->
            val parentId = place(spec.name, null, i)
            pathIds[spec.name] = parentId
            spec.children.forEachIndexed { j, child -> pathIds["${spec.name}/$child"] = place(child, parentId, j) }
        }
        existing.filter { it.id !in keep }.forEach {
            writes += Rows.categories.encode(it.copy(deleted = true, updatedAt = now), s.stored(Rows.categories.table, it.id))
        }

        val oldAliases = s.aliases.filter { !it.deleted }.sortedBy { it.priority }.toMutableList()
        document.aliases.forEachIndexed { i, spec ->
            val same = oldAliases.firstOrNull { it.pattern == spec.match && it.merchantName == spec.name }?.also { oldAliases.remove(it) }
            val row = (same ?: AliasRow(UUID.randomUUID().toString(), spec.match, spec.name, i, now)).copy(priority = i, updatedAt = now)
            if (row.copy(updatedAt = 0) != same?.copy(updatedAt = 0)) writes += Rows.aliases.encode(row, s.stored(Rows.aliases.table, row.id))
        }
        oldAliases.forEach { writes += Rows.aliases.encode(it.copy(deleted = true, updatedAt = now), s.stored(Rows.aliases.table, it.id)) }

        val oldRules = s.rules.filter { !it.deleted }.sortedBy { it.priority }.toMutableList()
        document.rules.forEachIndexed { i, spec ->
            val wanted = RuleRow(
                id = UUID.randomUUID().toString(),
                priority = i,
                merchantPattern = spec.merchant,
                minAmountCents = spec.minAmountCents,
                maxAmountCents = spec.maxAmountCents,
                accountId = spec.account?.let { accountIds.getValue(it) },
                categoryId = pathIds.getValue(spec.category),
                tagsCsv = spec.tags.joinToString(",").ifEmpty { null },
                updatedAt = now,
                ask = spec.ask,
            )
            val same = oldRules.firstOrNull { it.copy(id = wanted.id, priority = i, updatedAt = now) == wanted }?.also { oldRules.remove(it) }
            val row = same?.copy(priority = i, updatedAt = now) ?: wanted
            if (row.copy(updatedAt = 0) != same?.copy(updatedAt = 0)) writes += Rows.rules.encode(row, s.stored(Rows.rules.table, row.id))
        }
        oldRules.forEach { writes += Rows.rules.encode(it.copy(deleted = true, updatedAt = now), s.stored(Rows.rules.table, it.id)) }

        sync.write(writes, source)
        return result
    }
}
