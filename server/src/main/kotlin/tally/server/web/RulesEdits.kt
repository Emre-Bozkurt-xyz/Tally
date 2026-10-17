package tally.server.web

import tally.core.capture.Amounts
import tally.core.model.CategoryKinds
import tally.core.model.CategoryNode
import tally.core.model.CategoryRules
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.normalize.Alias
import tally.core.normalize.MerchantIds
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.ReplayCandidate
import tally.core.rules.ReplayChange
import tally.core.rules.Rule
import tally.core.rules.RuleReplay
import tally.core.rules.RulesEngine
import tally.core.sync.SyncChange
import tally.core.sync.SyncServer
import java.time.Clock
import java.util.UUID

fun RuleRow.toRule() = Rule(
    id = id, priority = priority, merchantPattern = merchantPattern, minAmountCents = minAmountCents,
    maxAmountCents = maxAmountCents, accountId = accountId, categoryId = categoryId,
    tags = tagsCsv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }, ask = ask,
)

/** Null if the stored pattern is unusable (it can't be saved that way, but stay safe). */
fun AliasRow.toAlias(): Alias? = runCatching { Alias(Regex(pattern), merchantName) }.getOrNull()

/** The rules page's edits: one row at a time, through the same sync path as everything else. */
class RulesEdits(private val sync: SyncServer, private val clock: Clock = Clock.systemUTC()) {
    fun snapshot() = Snapshot.load(sync)

    val Snapshot.activeRules get() = rules.filter { !it.deleted }.sortedBy { it.priority }
    val Snapshot.activeAliases get() = aliases.filter { !it.deleted }.sortedBy { it.priority }

    /** A rule as the form sends it; [id] blank for a new one. */
    data class RuleForm(
        val id: String?,
        val pattern: String,
        val min: String,
        val max: String,
        val accountId: String?,
        val categoryId: String,
        val tags: String,
        val ask: Boolean,
    )

    // ---------- Rules ----------

    /** Adds (at the end) or updates a rule. Returns an error message, or null when saved. */
    fun saveRule(form: RuleForm, source: String): String? {
        val s = snapshot()
        val pattern = form.pattern.trim()
        Rule.patternError(pattern)?.let { return it }
        val min = amount(form.min) { return it }
        val max = amount(form.max) { return it }
        if (min != null && max != null && min > max) return "The minimum is above the maximum"
        if (s.categoryTree.none { it.id == form.categoryId }) return "Pick a category"
        if (form.accountId != null && s.accounts.none { it.id == form.accountId }) return "That account no longer exists"
        val tags = form.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val existing = s.activeRules.find { it.id == form.id }
        val now = clock.millis()
        val row = RuleRow(
            id = existing?.id ?: UUID.randomUUID().toString(),
            priority = existing?.priority ?: ((s.activeRules.maxOfOrNull { it.priority } ?: -1) + 1),
            merchantPattern = pattern,
            minAmountCents = min,
            maxAmountCents = max,
            accountId = form.accountId,
            categoryId = form.categoryId,
            tagsCsv = tags.joinToString(",").ifEmpty { null },
            updatedAt = now,
            ask = form.ask,
        )
        if (existing != null && row.copy(updatedAt = 0) == existing.copy(updatedAt = 0)) return null
        sync.write(listOf(Rows.rules.encode(row, s.stored(Rows.rules.table, row.id))), source)
        return null
    }

    fun deleteRule(id: String, source: String) {
        val s = snapshot()
        val rule = s.activeRules.find { it.id == id } ?: return
        sync.write(listOf(Rows.rules.encode(rule.copy(deleted = true, updatedAt = clock.millis()), s.stored(Rows.rules.table, id))), source)
    }

    /** Moves a rule up ([by] = -1) or down (1); renumbers densely, writing only rows that moved. */
    fun moveRule(id: String, by: Int, source: String) {
        val s = snapshot()
        val now = clock.millis()
        val writes = reorder(s.activeRules, id, by, { it.id }, { it.priority }) { rule, i ->
            Rows.rules.encode(rule.copy(priority = i, updatedAt = now), s.stored(Rows.rules.table, rule.id))
        }
        if (writes.isNotEmpty()) sync.write(writes, source)
    }

    /** How many live transactions [rule] matches on its own (not counting rules above it). */
    fun matches(s: Snapshot, rule: RuleRow): Int {
        val engine = RulesEngine(listOf(rule.toRule()))
        return s.transactions.count { t ->
            val merchant = t.merchantId?.let { s.merchantsById[it]?.name }
            engine.match(merchant, t.amountCents, t.accountId) != null
        }
    }

    // ---------- Aliases ----------

    /** Adds (at the end) or updates an alias, then re-files merchants. Returns an error, or null. */
    fun saveAlias(id: String?, pattern: String, name: String, source: String): String? {
        val s = snapshot()
        val match = pattern.trim()
        val merchant = name.trim()
        if (match.isEmpty()) return "Enter a pattern"
        if (merchant.isEmpty()) return "Enter the name it becomes"
        runCatching { Regex(match) }.onFailure { return "Not a valid pattern: ${it.message?.lineSequence()?.firstOrNull()}" }
        if (s.activeAliases.any { it.id != id && it.pattern == match }) return "There's already an alias for that pattern"
        val existing = s.activeAliases.find { it.id == id }
        val row = AliasRow(
            id = existing?.id ?: UUID.randomUUID().toString(),
            pattern = match,
            merchantName = merchant,
            priority = existing?.priority ?: ((s.activeAliases.maxOfOrNull { it.priority } ?: -1) + 1),
            updatedAt = clock.millis(),
        )
        if (existing != null && row.copy(updatedAt = 0) == existing.copy(updatedAt = 0)) return null
        sync.write(listOf(Rows.aliases.encode(row, s.stored(Rows.aliases.table, row.id))), source)
        reapplyAliases(source)
        return null
    }

    fun deleteAlias(id: String, source: String) {
        val s = snapshot()
        val alias = s.activeAliases.find { it.id == id } ?: return
        sync.write(listOf(Rows.aliases.encode(alias.copy(deleted = true, updatedAt = clock.millis()), s.stored(Rows.aliases.table, id))), source)
        reapplyAliases(source)
    }

    fun moveAlias(id: String, by: Int, source: String) {
        val s = snapshot()
        val now = clock.millis()
        val writes = reorder(s.activeAliases, id, by, { it.id }, { it.priority }) { alias, i ->
            Rows.aliases.encode(alias.copy(priority = i, updatedAt = now), s.stored(Rows.aliases.table, alias.id))
        }
        if (writes.isEmpty()) return
        sync.write(writes, source)
        reapplyAliases(source)
    }

    /** How many live transactions' bank text [alias] matches. */
    fun matches(s: Snapshot, alias: AliasRow): Int {
        val regex = alias.toAlias()?.pattern ?: return 0
        return s.transactions.count { t -> t.merchantRaw?.let { regex.containsMatchIn(it.trim()) } == true }
    }

    /**
     * Re-files every transaction's merchant by the current aliases (what the phone does after
     * pulling alias changes), creating merchants as needed. Returns how many moved.
     */
    fun reapplyAliases(source: String): Int {
        val s = snapshot()
        val normalizer = MerchantNormalizer(s.activeAliases.mapNotNull { it.toAlias() })
        val now = clock.millis()
        val byName = s.merchantsById.values.filter { !it.deleted }.associateBy { it.name.lowercase() }.toMutableMap()
        val writes = mutableListOf<SyncChange>()
        var moved = 0
        for (t in s.transactions) {
            val raw = t.merchantRaw ?: continue
            if (t.merchantId == null && t.kind == Kind.TRANSFER.name) continue
            val name = normalizer.normalize(raw)
            val merchant = byName.getOrPut(name.lowercase()) {
                MerchantRow(MerchantIds.forName(name), name, null, 0, now).also { writes += Rows.merchants.encode(it, null) }
            }
            if (t.merchantId == merchant.id) continue
            writes += Rows.transactions.encode(t.copy(merchantId = merchant.id, updatedAt = now), s.stored(Rows.transactions.table, t.id))
            moved++
        }
        if (writes.isNotEmpty()) sync.write(writes, source)
        return moved
    }

    // ---------- Categories ----------

    private fun nodes(s: Snapshot) = s.categoryTree.map { CategoryNode(it.id, it.name, it.parentId) }

    fun addCategory(name: String, parentId: String?, source: String): String? {
        val s = snapshot()
        val trimmed = name.trim()
        CategoryRules.validate(nodes(s), trimmed, parentId, editingId = null)?.let { return it }
        val siblings = s.categoryTree.filter { it.parentId == parentId }
        val row = CategoryRow(UUID.randomUUID().toString(), trimmed, parentId, null, (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1, false, clock.millis())
        sync.write(listOf(Rows.categories.encode(row, null)), source)
        return null
    }

    /** Renames, moves (to another parent, last there), and sets the budget and pinned flag. */
    fun updateCategory(id: String, name: String, parentId: String?, budget: String, pinned: Boolean, source: String): String? {
        val s = snapshot()
        val current = s.categoryTree.find { it.id == id } ?: return "That category no longer exists"
        val trimmed = name.trim()
        CategoryRules.validate(nodes(s), trimmed, parentId, editingId = id)?.let { return it }
        val cents = amount(budget) { return it }
        if (cents != null && cents <= 0) return "A budget must be more than $0"
        val sortOrder = if (parentId != current.parentId) {
            (s.categoryTree.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            current.sortOrder
        }
        val row = current.copy(name = trimmed, parentId = parentId, sortOrder = sortOrder, monthlyBudgetCents = cents, pinned = pinned)
        if (row == current) return null
        sync.write(listOf(Rows.categories.encode(row.copy(updatedAt = clock.millis()), s.stored(Rows.categories.table, id))), source)
        return null
    }

    /** Past transactions keep pointing at it and still show its name. */
    fun deleteCategory(id: String, source: String): String? {
        val s = snapshot()
        val current = s.categoryTree.find { it.id == id } ?: return null
        CategoryRules.deleteError(nodes(s), id)?.let { return it }
        sync.write(listOf(Rows.categories.encode(current.copy(deleted = true, updatedAt = clock.millis()), s.stored(Rows.categories.table, id))), source)
        return null
    }

    fun moveCategory(id: String, by: Int, source: String) {
        val s = snapshot()
        val current = s.categoryTree.find { it.id == id } ?: return
        val siblings = s.categoryTree.filter { it.parentId == current.parentId }.sortedBy { it.sortOrder }
        val now = clock.millis()
        val writes = reorder(siblings, id, by, { it.id }, { it.sortOrder }) { category, i ->
            Rows.categories.encode(category.copy(sortOrder = i, updatedAt = now), s.stored(Rows.categories.table, category.id))
        }
        if (writes.isNotEmpty()) sync.write(writes, source)
    }

    // ---------- Applying rules to what's already there ----------

    fun replayPlan(s: Snapshot = snapshot()): List<ReplayChange> {
        val engine = RulesEngine(s.activeRules.map { it.toRule() })
        val candidates = s.transactions.map { t ->
            ReplayCandidate(
                id = t.id,
                merchant = t.merchantId?.let { s.merchantsById[it]?.name },
                amountCents = t.amountCents,
                accountId = t.accountId,
                categoryId = t.categoryId,
                captured = t.status == TransactionStatus.CAPTURED.name,
                transfer = t.kind == Kind.TRANSFER.name,
            )
        }
        return RuleReplay.plan(candidates, engine).filter { change -> s.categoryTree.any { it.id == change.categoryId } }
    }

    /** Files (or, for ask rules, suggests) what [replayPlan] found. Returns how many changed. */
    fun applyReplay(source: String): Int {
        val s = snapshot()
        val changes = replayPlan(s)
        val now = clock.millis()
        val writes = changes.mapNotNull { change ->
            val t = s.transactions.find { it.id == change.id } ?: return@mapNotNull null
            Rows.transactions.encode(
                t.copy(
                    categoryId = change.categoryId,
                    categorySource = CategorySource.RULE.name,
                    status = if (change.confirm) TransactionStatus.CONFIRMED.name else TransactionStatus.CAPTURED.name,
                    kind = CategoryKinds.kindFor(Kind.valueOf(t.kind), t.amountCents, s.isIncomeCategory(change.categoryId)).name,
                    updatedAt = now,
                ),
                s.stored(Rows.transactions.table, t.id),
            )
        }
        if (writes.isNotEmpty()) sync.write(writes, source)
        return writes.size
    }

    // ---------- Trying text against the rules ----------

    class Trial(val cleaned: String, val alias: AliasRow?, val rule: RuleRow?, val memory: MerchantRow?)

    /** What happens to bank text [raw] (and [amount], if given): the name it becomes, and what files it. */
    fun trial(s: Snapshot, raw: String, amount: String): Trial {
        val aliases = s.activeAliases
        val text = raw.trim()
        val cleaned = MerchantNormalizer(aliases.mapNotNull { it.toAlias() }).normalize(text)
        val alias = aliases.firstOrNull { it.toAlias()?.pattern?.containsMatchIn(text) == true }
        val cents = amount.takeIf { it.isNotBlank() }?.let { Amounts.parseCents(it) } ?: 0L
        val ruleId = RulesEngine(s.activeRules.map { it.toRule() }).match(cleaned, cents, null)?.id
        val memory = s.merchantsById.values.find { !it.deleted && it.name.equals(cleaned, ignoreCase = true) && it.lastCategoryId != null }
        return Trial(cleaned, alias, s.activeRules.find { it.id == ruleId }, memory)
    }

    // ---------- Helpers ----------

    /** "15", "15.00" or blank (null) to cents; [error] is called (and must leave) on anything else. */
    private inline fun amount(text: String, error: (String) -> Nothing): Long? {
        val t = text.trim().removePrefix("$")
        if (t.isEmpty()) return null
        if (!AMOUNT.matches(t)) error("\"$text\" isn't an amount")
        return Amounts.parseCents(t) ?: error("\"$text\" isn't an amount")
    }

    private fun <T> reorder(ordered: List<T>, id: String, by: Int, idOf: (T) -> String, position: (T) -> Int, write: (T, Int) -> SyncChange): List<SyncChange> {
        val list = ordered.toMutableList()
        val from = list.indexOfFirst { idOf(it) == id }
        val to = from + by
        if (from < 0 || to !in list.indices) return emptyList()
        list.add(to, list.removeAt(from))
        return list.mapIndexedNotNull { i, item -> if (position(item) == i) null else write(item, i) }
    }

    private companion object {
        val AMOUNT = Regex("\\d+(?:\\.\\d{1,2})?")
    }
}
