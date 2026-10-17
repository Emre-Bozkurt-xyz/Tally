package tally.server.web

import tally.core.llm.CategoryModel
import tally.core.llm.DailyLimitReached
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.normalize.Alias
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.MerchantHistory
import tally.core.rules.RuleSuggestionRules
import tally.core.rules.RuleSuggestions
import tally.core.rules.SuggestedAlias
import tally.core.rules.SuggestedRule
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Rule suggestions for the rules page: from your history, or from an LLM (a stronger model
 * than the categorizer's, since it writes regexes). Each comes with what it would change;
 * nothing is saved until you tick it.
 */
class RuleSuggester(
    private val rules: RulesEdits,
    private val model: CategoryModel?,
    private val dailyLimit: Int = 20,
    private val clock: Clock = Clock.systemUTC(),
) {
    val canAsk get() = model != null

    /** Suggestions with a line each on what they'd change, and the LLM's that didn't pass the checks. */
    class Shown(
        val aliases: List<Pair<SuggestedAlias, String>>,
        val rules: List<Pair<SuggestedRule, String>>,
        val dropped: List<String>,
    ) {
        val isEmpty get() = aliases.isEmpty() && rules.isEmpty()
    }

    fun fromHistory(s: Snapshot): Shown {
        val covered = patterns(s)
        val suggested = RuleSuggestionRules.fromHistory(history(s)) { name -> covered.any { it.matches(name) } }
        return show(s, RuleSuggestions(rules = suggested), emptyList())
    }

    /** Asks the model. Throws [DailyLimitReached], or IOException when the model can't be reached. */
    fun fromModel(s: Snapshot): Shown {
        val model = model ?: throw IOException("No ANTHROPIC_API_KEY on the server")
        countCall()
        val history = history(s)
        val paths = s.categoryTree.mapNotNull { s.path(it.id) }
        val aliases = with(rules) { s.activeAliases }.mapNotNull { it.toAlias() }
        val active = with(rules) { s.activeRules }
        val reply = model.ask(
            RuleSuggestionRules.system,
            RuleSuggestionRules.user(history, paths, aliases, active.map { it.toRule() }) { s.path(it) },
        )
        val raw = RuleSuggestionRules.parse(reply) ?: throw IOException("The model's answer wasn't the JSON asked for")
        val checked = RuleSuggestionRules.check(raw, history, paths.toSet(), aliases) { name -> fileOf(s, name) }
        return show(s, checked.suggestions, checked.dropped)
    }

    /** Adds the ticked suggestions: aliases first (they rename merchants), then rules at the end. Returns problems. */
    fun add(aliases: List<SuggestedAlias>, rules: List<SuggestedRule>, source: String): List<String> {
        val problems = mutableListOf<String>()
        aliases.forEach { a -> this.rules.saveAlias(null, a.match, a.name, source)?.let { problems += "Alias ${a.match}: $it" } }
        val s = this.rules.snapshot()
        val byPath = s.categoryTree.associateBy { s.path(it.id) }
        rules.forEach { r ->
            val category = byPath[r.category]
            if (category == null) {
                problems += "Rule ${r.merchant}: ${r.category} no longer exists"
                return@forEach
            }
            val form = RulesEdits.RuleForm(null, r.merchant, "", "", null, category.id, "", r.ask)
            this.rules.saveRule(form, source)?.let { problems += "Rule ${r.merchant}: $it" }
        }
        return problems
    }

    /** Every merchant with transactions: its bank texts and how you filed it. Busiest first. */
    fun history(s: Snapshot): List<MerchantHistory> = s.transactions
        .filter { it.merchantId != null && it.kind != Kind.TRANSFER.name }
        .groupBy { it.merchantId!! }
        .mapNotNull { (merchantId, list) ->
            val name = s.merchantsById[merchantId]?.name ?: return@mapNotNull null
            val filed = list.filter { it.status != TransactionStatus.CAPTURED.name && it.categoryId != null }
                .groupingBy { s.path(it.categoryId)!! }.eachCount()
            val waiting = list.count { it.status == TransactionStatus.CAPTURED.name || it.categoryId == null }
            list.size to MerchantHistory(name, list.mapNotNull { it.merchantRaw }.distinct().take(3), filed, waiting)
        }
        .sortedByDescending { it.first }
        .take(MAX_MERCHANTS)
        .map { it.second }

    private fun show(s: Snapshot, suggestions: RuleSuggestions, dropped: List<String>): Shown {
        val existing = with(rules) { s.activeAliases }.mapNotNull { it.toAlias() }
        val after = MerchantNormalizer(suggestions.aliases.map { Alias(Regex(it.match), it.name) } + existing)
        val waiting = s.transactions.filter { it.status == TransactionStatus.CAPTURED.name || it.categoryId == null }.map { it.id }.toSet()
        // Each transaction's merchant name as it would be after the suggested aliases.
        val names = s.transactions.filter { it.kind != Kind.TRANSFER.name }.mapNotNull { t ->
            val name = t.merchantRaw?.let { after.normalize(it) } ?: t.merchantId?.let { s.merchantsById[it]?.name }
            name?.let { t.id to it }
        }
        val aliases = suggestions.aliases.map { a ->
            val regex = Regex(a.match)
            val n = s.transactions.count { t -> t.merchantRaw?.let { regex.containsMatchIn(it.trim()) } == true }
            a to "renames $n transaction${if (n == 1) "" else "s"}"
        }
        val ruleViews = suggestions.rules.map { r ->
            val regex = Regex(r.merchant, RegexOption.IGNORE_CASE)
            val matched = names.filter { regex.matches(it.second) }.map { it.first }
            val inbox = matched.count { it in waiting }
            r to "matches ${matched.size}" + if (inbox > 0) ", $inbox waiting to be ${if (r.ask) "suggested" else "filed"}" else ""
        }
        return Shown(aliases, ruleViews, dropped)
    }

    private fun patterns(s: Snapshot): List<Regex> =
        with(rules) { s.activeRules }.mapNotNull { runCatching { Regex(it.merchantPattern, RegexOption.IGNORE_CASE) }.getOrNull() }

    /** The category path the existing rules file [name] under (first match), ignoring amount limits. */
    private fun fileOf(s: Snapshot, name: String): String? = with(rules) { s.activeRules }.firstOrNull { rule ->
        runCatching { Regex(rule.merchantPattern, RegexOption.IGNORE_CASE).matches(name) }.getOrDefault(false)
    }?.let { s.path(it.categoryId) }

    private var day: LocalDate? = null
    private var callsToday = 0

    @Synchronized
    private fun countCall() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        if (today != day) {
            day = today
            callsToday = 0
        }
        if (callsToday >= dailyLimit) throw DailyLimitReached(dailyLimit)
        callsToday++
    }

    private companion object {
        const val MAX_MERCHANTS = 150
    }
}
