package tally.app.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.AliasSpec
import tally.core.rules.CategorySpec
import tally.core.rules.Rule
import tally.core.rules.RuleSpec
import tally.core.rules.RulesDocument
import tally.core.rules.RulesToml
import tally.core.rules.TomlImport
import java.time.Clock
import java.util.UUID

/** Rules and aliases: the TOML round trip and "Always do this". */
class RulesRepository(
    private val db: TallyDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val rules = db.ruleDao()
    private val aliases = db.aliasDao()
    private val categories = db.categoryDao()

    fun observeRules(): Flow<List<RuleEntity>> = rules.observeActive()

    fun observeAliases(): Flow<List<AliasEntity>> = aliases.observeActive()

    /** Checks a TOML file without saving it. */
    suspend fun validateToml(text: String): TomlImport =
        RulesToml.import(text, db.accountDao().getActive().map { it.name }.toSet())

    /** Adds ([rule].id not saved yet) or updates a rule. Returns an error message, or null. */
    suspend fun saveRule(rule: RuleEntity): String? {
        Rule.patternError(rule.merchantPattern)?.let { return it }
        val min = rule.minAmountCents
        val max = rule.maxAmountCents
        if ((min != null && min < 0) || (max != null && max < 0)) return "Amounts can't be negative"
        if (min != null && max != null && min > max) return "The minimum is above the maximum"
        if (rule.tagsCsv.orEmpty().split(',').any { it.isNotEmpty() && it.isBlank() }) return "Empty tag"
        val existing = rules.getActive()
        val priority = existing.find { it.id == rule.id }?.priority ?: ((existing.maxOfOrNull { it.priority } ?: -1) + 1)
        rules.upsertAll(listOf(rule.copy(priority = priority, updatedAt = clock.millis())))
        return null
    }

    suspend fun deleteRule(id: String) {
        rules.getActive().find { it.id == id }?.let { rules.upsertAll(listOf(it.copy(deleted = true, updatedAt = clock.millis()))) }
    }

    /** Swaps a rule with the one above ([by] = -1) or below (1). */
    suspend fun moveRule(id: String, by: Int) {
        val ordered = rules.getActive().toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in ordered.indices) return
        ordered.add(to, ordered.removeAt(from))
        val now = clock.millis()
        rules.upsertAll(ordered.mapIndexedNotNull { i, rule -> if (rule.priority == i) null else rule.copy(priority = i, updatedAt = now) })
    }

    /** Adds or updates an alias, then renames past merchants to match. Returns an error, or null. */
    suspend fun saveAlias(id: String?, pattern: String, merchantName: String): String? {
        Rule.patternError(pattern)?.let { return it }
        if (merchantName.isBlank()) return "Enter the merchant name"
        val existing = aliases.getActive()
        if (existing.any { it.id != id && it.pattern == pattern }) return "There's already an alias for that pattern"
        val now = clock.millis()
        val alias = existing.find { it.id == id }?.copy(pattern = pattern, merchantName = merchantName.trim(), updatedAt = now)
            ?: AliasEntity(UUID.randomUUID().toString(), pattern, merchantName.trim(), (existing.maxOfOrNull { it.priority } ?: -1) + 1, now)
        aliases.upsertAll(listOf(alias))
        reapplyAliases()
        return null
    }

    suspend fun deleteAlias(id: String) {
        aliases.getActive().find { it.id == id }?.let { aliases.upsertAll(listOf(it.copy(deleted = true, updatedAt = clock.millis()))) }
        reapplyAliases()
    }

    suspend fun moveAlias(id: String, by: Int) {
        val ordered = aliases.getActive().toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in ordered.indices) return
        ordered.add(to, ordered.removeAt(from))
        val now = clock.millis()
        aliases.upsertAll(ordered.mapIndexedNotNull { i, alias -> if (alias.priority == i) null else alias.copy(priority = i, updatedAt = now) })
        reapplyAliases()
    }

    /** Raw merchant strings from recent purchases, for "make an alias from this". */
    suspend fun recentMerchantRaws(): List<String> = db.transactionDao().recentMerchantRaws(30)

    /**
     * Re-runs merchant cleanup with the current aliases over every captured transaction, so
     * the ledger shows the new names. Merchants are found or created by name.
     */
    suspend fun reapplyAliases() = db.useWriterConnection { connection ->
        connection.immediateTransaction {
            val normalizer = MerchantNormalizer(aliases.getActive().mapNotNull { it.toAlias() })
            val merchants = db.merchantDao()
            val now = clock.millis()
            for (ref in db.transactionDao().merchantRefs()) {
                val name = normalizer.normalize(ref.merchantRaw)
                val merchant = merchants.findByName(name)
                    ?: MerchantEntity(UUID.randomUUID().toString(), name, null, 0, now).also { merchants.upsert(it) }
                if (merchant.id != ref.merchantId) db.transactionDao().setMerchant(ref.id, merchant.id, now)
            }
        }
    }

    /** The categories, aliases and rules as an editable TOML file. */
    suspend fun exportToml(): String {
        val tree = categories.getActive().inTreeOrder()
        val paths = categoryPaths(tree)
        val accountNames = db.accountDao().getAll().associate { it.id to it.name }
        val document = RulesDocument(
            categories = tree.filter { it.parentId == null }.map { parent ->
                CategorySpec(parent.name, tree.filter { it.parentId == parent.id }.map { it.name })
            },
            aliases = aliases.getActive().map { AliasSpec(it.pattern, it.merchantName) },
            rules = rules.getActive().mapNotNull { rule ->
                RuleSpec(
                    merchant = rule.merchantPattern,
                    category = paths[rule.categoryId] ?: return@mapNotNull null,
                    minAmountCents = rule.minAmountCents,
                    maxAmountCents = rule.maxAmountCents,
                    account = rule.accountId?.let { accountNames[it] },
                    tags = rule.toRule().tags,
                )
            },
        )
        return RulesToml.export(document)
    }

    /**
     * Validates [text] and, if it's valid, makes the database match it in one transaction:
     * categories by path (existing ones keep their ids, so old transactions keep their category;
     * ones missing from the file are deleted), and aliases and rules in file order.
     */
    suspend fun importToml(text: String): TomlImport {
        val accounts = db.accountDao().getActive()
        val result = RulesToml.import(text, accounts.map { it.name }.toSet())
        if (result is TomlImport.Ok) {
            db.useWriterConnection { connection ->
                connection.immediateTransaction { apply(result.document, accounts.associate { it.name to it.id }) }
            }
            reapplyAliases()
        }
        return result
    }

    /**
     * "Always do this": from now on, [merchantName] goes to [categoryId] (only up to
     * [maxAmountCents] if set). The new rule runs before all others. With [applyToPast], past
     * transactions from that merchant get the category too; returns how many changed.
     */
    suspend fun alwaysCategorize(
        merchantId: String,
        merchantName: String,
        categoryId: String,
        maxAmountCents: Long?,
        applyToPast: Boolean,
    ): Int = db.useWriterConnection { connection ->
        connection.immediateTransaction {
            val now = clock.millis()
            val first = rules.getActive().minOfOrNull { it.priority } ?: 0
            rules.upsertAll(
                listOf(
                    RuleEntity(
                        id = UUID.randomUUID().toString(),
                        priority = first - 1,
                        merchantPattern = Rule.literal(merchantName),
                        minAmountCents = null,
                        maxAmountCents = maxAmountCents,
                        accountId = null,
                        categoryId = categoryId,
                        tagsCsv = null,
                        updatedAt = now,
                    ),
                ),
            )
            if (applyToPast) {
                db.transactionDao().categorizeMerchant(merchantId, categoryId, maxAmountCents ?: Long.MAX_VALUE, now)
            } else {
                0
            }
        }
    }

    private suspend fun apply(document: RulesDocument, accountIds: Map<String, String>) {
        val now = clock.millis()
        val existing = categories.getActive()
        val keep = mutableSetOf<String>()
        val pathIds = mutableMapOf<String, String>()
        val writes = mutableListOf<CategoryEntity>()

        fun place(name: String, parentId: String?, sortOrder: Int): String {
            val found = existing.find { it.parentId == parentId && it.name == name }
            val wanted = found?.copy(sortOrder = sortOrder)
                ?: CategoryEntity(UUID.randomUUID().toString(), name, parentId, null, sortOrder, false, now)
            if (wanted != found) writes += wanted.copy(updatedAt = now)
            keep += wanted.id
            return wanted.id
        }

        document.categories.forEachIndexed { i, spec ->
            val parentId = place(spec.name, null, i)
            pathIds[spec.name] = parentId
            spec.children.forEachIndexed { j, child -> pathIds["${spec.name}/$child"] = place(child, parentId, j) }
        }
        writes += existing.filter { it.id !in keep }.map { it.copy(deleted = true, updatedAt = now) }
        categories.upsertAll(writes)

        // Aliases and rules: reuse identical rows (keeps ids stable for sync), delete the rest.
        val oldAliases = aliases.getActive().toMutableList()
        val newAliases = document.aliases.mapIndexed { i, spec ->
            val same = oldAliases.firstOrNull { it.pattern == spec.match && it.merchantName == spec.name }?.also { oldAliases.remove(it) }
            (same ?: AliasEntity(UUID.randomUUID().toString(), spec.match, spec.name, i, now)).copy(priority = i, updatedAt = now)
        }
        aliases.upsertAll(newAliases + oldAliases.map { it.copy(deleted = true, updatedAt = now) })

        val oldRules = rules.getActive().toMutableList()
        val newRules = document.rules.mapIndexed { i, spec ->
            val wanted = RuleEntity(
                id = UUID.randomUUID().toString(),
                priority = i,
                merchantPattern = spec.merchant,
                minAmountCents = spec.minAmountCents,
                maxAmountCents = spec.maxAmountCents,
                accountId = spec.account?.let { accountIds.getValue(it) },
                categoryId = pathIds.getValue(spec.category),
                tagsCsv = spec.tags.joinToString(",").ifEmpty { null },
                updatedAt = now,
            )
            val same = oldRules.firstOrNull { it.copy(id = wanted.id, priority = i, updatedAt = now) == wanted }
                ?.also { oldRules.remove(it) }
            same?.copy(priority = i, updatedAt = now) ?: wanted
        }
        rules.upsertAll(newRules + oldRules.map { it.copy(deleted = true, updatedAt = now) })
    }

    private fun categoryPaths(tree: List<CategoryEntity>): Map<String, String> {
        val byId = tree.associateBy { it.id }
        return tree.associate { category ->
            category.id to (category.parentId?.let { "${byId[it]?.name}/${category.name}" } ?: category.name)
        }
    }
}
