package tally.server.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import tally.core.llm.CategorizeRequest
import tally.core.llm.CategorizeService
import tally.core.model.CategoryKinds
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.Source
import tally.core.model.TransactionStatus
import tally.core.normalize.Alias
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.Categorizer
import tally.core.rules.Confidence
import tally.core.rules.MerchantMemory
import tally.core.rules.Rule
import tally.core.rules.RulesEngine
import tally.core.statement.MatchCandidate
import tally.core.statement.RbcStatements
import tally.core.statement.Statement
import tally.core.statement.StatementKind
import tally.core.statement.StatementLine
import tally.core.statement.StatementMatcher
import tally.core.sync.SyncChange
import tally.core.sync.SyncServer
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What importing a statement into an account would do (the preview), and does (the import). */
class ImportPlan(
    val statement: Statement,
    val accountId: String?,
    /** Statement rows already in Tally, with any change the import makes (or null for none). */
    val matched: List<Matched>,
    /** Rows that become new transactions, categorized as they'll be saved. */
    val added: List<Added>,
    /** Rows imported before (same statement again). */
    val alreadyImported: Int,
    /** Transactions on this account in the period that the statement doesn't have. */
    val missing: List<TransactionRow>,
) {
    class Matched(val line: StatementLine, val transaction: TransactionRow, val updated: TransactionRow?)
    class Added(val line: StatementLine, val transaction: TransactionRow, val merchant: MerchantRow?)
}

/**
 * Statement import (Phase 5): RBC PDF statements read on the server, matched against what
 * Tally captured, and the difference written as synced rows. Matched transactions take the
 * statement's amount (tips, holds) and become RECONCILED; rows Tally never saw become CSV
 * transactions, categorized by rules and merchant memory, with the rest left in the inbox
 * (and, with an LLM configured, a suggestion arriving shortly after).
 */
class StatementImport(
    private val sync: SyncServer,
    private val clock: Clock,
    private val zone: ZoneId,
    private val categorize: CategorizeService? = null,
    private val background: CoroutineScope? = null,
) {
    private class Parsed(val statement: Statement, val at: Long)

    private val parsed = ConcurrentHashMap<String, Parsed>()

    /** Reads a PDF; returns a token for the preview and import steps, or null if it isn't a statement Tally knows. */
    fun read(pdf: ByteArray): String? {
        val statement = RbcStatements.parse(pdfText(pdf)) ?: return null
        return stash(statement)
    }

    /** Keeps a parsed statement for the preview and import steps (an hour); returns its token. */
    fun stash(statement: Statement): String {
        val now = clock.millis()
        parsed.entries.removeIf { now - it.value.at > KEEP_MS }
        return UUID.randomUUID().toString().also { parsed[it] = Parsed(statement, now) }
    }

    fun statement(token: String): Statement? = parsed[token]?.statement

    /** The account a statement belongs to: by a last-4 the phone learned, else by the account's name. */
    fun guessAccount(s: Snapshot, statement: Statement): String? {
        s.accountByLast4[statement.last4]?.takeIf { id -> s.accounts.any { it.id == id } }?.let { return it }
        val hint = if (statement.kind == StatementKind.CREDIT_CARD) Regex("(?i)visa|credit|mastercard") else Regex("(?i)chequing|checking|debit")
        return s.accounts.singleOrNull { hint.containsMatchIn(it.name) }?.id
    }

    fun plan(s: Snapshot, statement: Statement, accountId: String?): ImportPlan {
        val now = clock.millis()
        val ids = lineIds(statement)
        val existingIds = s.transactions.map { it.id }.toSet()
        val fresh = statement.lines.indices.filter { ids[it] !in existingIds }

        // Candidates: this account's transactions, and captures whose account is unknown.
        val window = statement.from.minusDays(7)..statement.to.plusDays(2)
        val candidates = s.transactions.filter { t ->
            t.id !in ids && (t.accountId == accountId || t.accountId == null) && s.date(t) in window
        }
        val byId = candidates.associateBy { it.id }
        val freshLines = fresh.map { statement.lines[it] }
        val result = StatementMatcher.match(
            freshLines,
            candidates.map { MatchCandidate(it.id, it.amountCents, s.date(it), listOfNotNull(s.merchantName(it), it.merchantRaw).joinToString(" ")) },
            statement.from,
            statement.to.minusDays(2),
        )

        val matched = result.matched.map { (i, candidate) ->
            val line = freshLines[i]
            val t = byId.getValue(candidate.id)
            val wanted = t.copy(
                amountCents = line.amountCents,
                accountId = t.accountId ?: accountId,
                // Captures still waiting for a category stay in the inbox.
                status = if (t.status == TransactionStatus.CONFIRMED.name) TransactionStatus.RECONCILED.name else t.status,
            )
            ImportPlan.Matched(line, t, wanted.takeIf { it != t }?.copy(updatedAt = now))
        }

        val categorizer = Categorizer(RulesEngine(s.rules.filter { !it.deleted }.map { it.toRule() }))
        val normalizer = MerchantNormalizer(s.aliases.filter { !it.deleted }.sortedBy { it.priority }.mapNotNull { a ->
            runCatching { Alias(Regex(a.pattern), a.merchantName) }.getOrNull()
        })
        val merchantsByName = s.merchantsById.values.filter { !it.deleted }.associateBy { it.name }.toMutableMap()
        val added = result.unmatched.map { i ->
            val line = freshLines[i]
            val id = ids[fresh[i]]
            val occurredAt = line.date.atTime(NOON).atZone(zone).toInstant().toEpochMilli()
            val base = TransactionRow(
                id = id, amountCents = line.amountCents, currency = "CAD", occurredAt = occurredAt, zoneId = zone.id,
                accountId = accountId, merchantId = null, merchantRaw = line.merchant, categoryId = null, note = null,
                source = Source.CSV.name, status = TransactionStatus.CONFIRMED.name, categorySource = null,
                kind = line.kind.name, updatedAt = now,
            )
            if (line.kind == Kind.TRANSFER) {
                // Between your own accounts: no category; the description says what it was.
                return@map ImportPlan.Added(line, base.copy(note = line.description), null)
            }
            val name = normalizer.normalize(line.merchant)
            val merchant = merchantsByName.getOrPut(name) { MerchantRow(UUID.randomUUID().toString(), name, null, 0, now) }
            // Money to and from people is asked about: memory suggests, only a plain rule files.
            val suggestion = categorizer.categorize(
                name, line.amountCents, accountId, MerchantMemory(merchant.lastCategoryId, merchant.confirmCount), trustMemory = !line.person,
            )
            val categoryId = suggestion.categoryId?.takeIf { id -> s.categoryTree.any { it.id == id } }
            ImportPlan.Added(
                line,
                base.copy(
                    merchantId = merchant.id,
                    categoryId = categoryId,
                    categorySource = suggestion.source?.name?.takeIf { categoryId != null },
                    kind = categoryId?.let { CategoryKinds.kindFor(line.kind, line.amountCents, s.isIncomeCategory(it)).name } ?: line.kind.name,
                    note = line.description.takeIf { line.kind == Kind.INCOME },
                    status = if (categoryId != null && suggestion.confidence == Confidence.HIGH) {
                        TransactionStatus.CONFIRMED.name
                    } else {
                        TransactionStatus.CAPTURED.name
                    },
                ),
                merchant.takeIf { it.id !in s.merchantsById },
            )
        }

        val missing = result.missing.mapNotNull { byId[it.id] }.filter { it.accountId == accountId }
        return ImportPlan(statement, accountId, matched, added, statement.lines.size - fresh.size, missing)
    }

    /** Writes the plan. Returns it (as written). */
    fun commit(token: String, accountId: String, source: String): ImportPlan? {
        val statement = statement(token) ?: return null
        val s = Snapshot.load(sync)
        if (s.accounts.none { it.id == accountId }) return null
        val plan = plan(s, statement, accountId)
        val writes = mutableListOf<SyncChange>()
        plan.matched.forEach { m -> m.updated?.let { writes += Rows.transactions.encode(it, s.stored(Rows.transactions.table, it.id)) } }
        plan.added.forEach { a ->
            a.merchant?.let { writes += Rows.merchants.encode(it, null) }
            writes += Rows.transactions.encode(a.transaction, null)
        }
        sync.write(writes.distinctBy { it.table to it.id }, source)
        suggestLater(plan)
        return plan
    }

    /** Asks the LLM about new purchases nothing else could categorize, after the import returns. */
    private fun suggestLater(plan: ImportPlan) {
        val service = categorize ?: return
        val scope = background ?: return
        val waiting = plan.added.filter { it.transaction.categoryId == null && it.transaction.kind == Kind.EXPENSE.name && it.transaction.amountCents > 0 }
        if (waiting.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (added in waiting) {
                try {
                    val s = Snapshot.load(sync)
                    val t = s.transactions.find { it.id == added.transaction.id } ?: continue
                    if (t.categoryId != null || t.status != TransactionStatus.CAPTURED.name) continue
                    val merchant = t.merchantId?.let { s.merchantsById[it]?.name } ?: continue
                    val paths = s.categoryTree.associateBy { s.path(it.id)!! }
                    val answer = service.categorize(CategorizeRequest(merchant, t.amountCents, paths.keys.toList())).category ?: continue
                    val categoryId = paths[answer]?.id ?: continue
                    sync.write(
                        listOf(
                            Rows.transactions.encode(
                                t.copy(categoryId = categoryId, categorySource = CategorySource.LLM.name, updatedAt = clock.millis()),
                                s.stored(Rows.transactions.table, t.id),
                            ),
                        ),
                        "server",
                    )
                } catch (e: Exception) {
                    log.warn("No suggestion for an imported row: ${e.message}")
                    if (e is tally.core.llm.DailyLimitReached) break
                }
            }
        }
    }

    /** Stable ids per row, so importing the same statement again finds what it added. */
    private fun lineIds(statement: Statement): List<String> {
        val seen = mutableMapOf<String, Int>()
        return statement.lines.map { line ->
            val key = "${statement.kind}:${statement.last4}:${line.date}:${line.amountCents}:${line.description}"
            val n = seen.merge(key, 1, Int::plus)!!
            UUID.nameUUIDFromBytes("tally:statement:$key:$n".toByteArray()).toString()
        }
    }

    private fun RuleRow.toRule() = Rule(
        id = id, priority = priority, merchantPattern = merchantPattern, minAmountCents = minAmountCents,
        maxAmountCents = maxAmountCents, accountId = accountId, categoryId = categoryId,
        tags = tagsCsv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }, ask = ask,
    )

    companion object {
        /** The PDF's text in reading order, which is what [RbcStatements] expects. */
        fun pdfText(pdf: ByteArray): String =
            Loader.loadPDF(pdf).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) }

        private val NOON: LocalTime = LocalTime.NOON
        private const val KEEP_MS = 60 * 60_000L
        private val log = LoggerFactory.getLogger(StatementImport::class.java)
    }
}
