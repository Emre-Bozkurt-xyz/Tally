package tally.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tally.core.normalize.Alias
import tally.core.normalize.MerchantNormalizer

/** One merchant as suggestions see it: its name, the bank texts behind it, and how you've filed it. */
data class MerchantHistory(
    val name: String,
    /** A few raw texts ("DOORDASHWENDYS"), for aliases to match. */
    val rawTexts: List<String>,
    /** Category path ("Food/Coffee") to how many times you filed it there. */
    val filed: Map<String, Int>,
    /** Transactions still waiting in the inbox or without a category. */
    val waiting: Int,
)

@Serializable
data class SuggestedAlias(val match: String, val name: String, val why: String = "")

@Serializable
data class SuggestedRule(val merchant: String, val category: String, val ask: Boolean = false, val why: String = "")

@Serializable
data class RuleSuggestions(val aliases: List<SuggestedAlias> = emptyList(), val rules: List<SuggestedRule> = emptyList())

/** Suggestions that passed the checks, and why the others didn't. */
data class CheckedSuggestions(val suggestions: RuleSuggestions, val dropped: List<String>)

/**
 * Rule suggestions, all reviewed by you before anything changes. From your history: a merchant
 * you filed the same way [MIN_TIMES]+ times that no rule covers. From the LLM: aliases for
 * squashed bank text and rules for merchants, checked against your actual data first.
 */
object RuleSuggestionRules {
    const val MIN_TIMES = 3
    private const val MAX_EACH = 30

    /** [covered] says whether an existing rule already matches a merchant name. */
    fun fromHistory(history: List<MerchantHistory>, covered: (String) -> Boolean): List<SuggestedRule> =
        history.mapNotNull { m ->
            val total = m.filed.values.sum()
            val (category, times) = m.filed.maxByOrNull { it.value } ?: return@mapNotNull null
            // Always the same category (allowing one odd one out in ten).
            if (times < MIN_TIMES || times * 10 < total * 9 || covered(m.name)) return@mapNotNull null
            times to SuggestedRule(Rule.literal(m.name), category, ask = false, why = "You filed it under $category $times times")
        }.sortedByDescending { it.first }.map { it.second }.take(MAX_EACH)

    // ---------- The LLM's part ----------

    val system = """
        You help tidy the rules of a personal expense tracker in Canada. Bank and card texts are
        cryptic: squashed, truncated, with store numbers and cities ("DOORDASHWENDYS",
        "HOSPITALITY-TIM HORTONS HAMILTON ON"). Two tools fix that:
        - aliases: a regex (Java syntax, use (?i) for case-insensitive) searched in the raw bank
          text; the first matching alias gives the merchant its clean name.
        - rules: a regex that must match the whole clean merchant name (case-insensitive), filing
          it under one category path from the list. "ask": true makes the rule only a suggestion
          the person confirms; use it for people (e-Transfers) and merchants filed several ways.
        Propose aliases only where a clean, recognizable name is clearly better than the current
        one, and rules only for merchants whose category is clear from their history or name.
        Don't repeat existing aliases or rules. Prefer one alias covering many texts of the same
        merchant (e.g. every DOORDASH* text becomes "DoorDash").
        Answer with only a JSON object: {"aliases": [{"match", "name", "why"}], "rules":
        [{"merchant", "category", "ask", "why"}]}. "why" is a short reason. Empty lists are fine.
    """.trimIndent()

    fun user(history: List<MerchantHistory>, categories: List<String>, aliases: List<Alias>, rules: List<Rule>, categoryOf: (String) -> String?): String =
        buildJsonObject {
            put("categories", JsonArray(categories.map { JsonPrimitive(it) }))
            put("existingAliases", buildJsonArray { aliases.forEach { add(buildJsonObject { put("match", it.pattern.pattern); put("name", it.name) }) } })
            put("existingRules", buildJsonArray { rules.forEach { r -> add(buildJsonObject { put("merchant", r.merchantPattern); put("category", categoryOf(r.categoryId)) }) } })
            put(
                "merchants",
                buildJsonArray {
                    history.forEach { m ->
                        add(
                            buildJsonObject {
                                put("name", m.name)
                                put("bankTexts", JsonArray(m.rawTexts.map { JsonPrimitive(it) }))
                                put("filed", JsonObject(m.filed.mapValues { JsonPrimitive(it.value) }))
                                put("waiting", m.waiting)
                            },
                        )
                    }
                },
            )
        }.toString()

    /** The JSON object in the model's reply (it may wrap it in prose or a code fence), or null. */
    fun parse(reply: String): RuleSuggestions? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(RuleSuggestions.serializer(), reply.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * Keeps what's usable: patterns that compile, aliases that match some bank text and change
     * its name, rules for known categories that match some merchant (as named after the
     * suggested aliases) and that no existing rule already files the same way.
     */
    fun check(
        raw: RuleSuggestions,
        history: List<MerchantHistory>,
        categories: Set<String>,
        existingAliases: List<Alias>,
        existingRule: (String) -> String?,
    ): CheckedSuggestions {
        val dropped = mutableListOf<String>()
        val current = MerchantNormalizer(existingAliases)
        val texts = history.flatMap { it.rawTexts }

        val aliases = raw.aliases.filter { a ->
            val regex = runCatching { Regex(a.match) }.getOrNull()
            when {
                a.name.isBlank() -> false.also { dropped += "Alias ${a.match}: no name" }
                regex == null -> false.also { dropped += "Alias ${a.match}: not a valid pattern" }
                existingAliases.any { it.pattern.pattern == a.match } -> false.also { dropped += "Alias ${a.match}: already exists" }
                texts.none { regex.containsMatchIn(it) } -> false.also { dropped += "Alias ${a.match}: matches none of your transactions" }
                texts.filter { regex.containsMatchIn(it) }.all { current.normalize(it) == a.name.trim() } ->
                    false.also { dropped += "Alias ${a.match}: changes no names" }
                else -> true
            }
        }.distinctBy { it.match }.take(MAX_EACH).map { it.copy(name = it.name.trim()) }

        // Names as they'd be with the suggested aliases in front of the existing ones.
        val after = MerchantNormalizer(aliases.map { Alias(Regex(it.match), it.name) } + existingAliases)
        val names = (history.map { it.name } + texts.map { after.normalize(it) }).toSet()

        val rules = raw.rules.filter { r ->
            val pattern = r.merchant.trim()
            val error = Rule.patternError(pattern)
            val regex = if (error == null) Regex(pattern, RegexOption.IGNORE_CASE) else null
            when {
                error != null || regex == null -> false.also { dropped += "Rule ${r.merchant}: $error" }
                r.category !in categories -> false.also { dropped += "Rule ${r.merchant}: unknown category ${r.category}" }
                names.none { regex.matches(it) } -> false.also { dropped += "Rule ${r.merchant}: matches none of your merchants" }
                names.filter { regex.matches(it) }.all { existingRule(it) == r.category } ->
                    false.also { dropped += "Rule ${r.merchant}: your rules already file these there" }
                else -> true
            }
        }.distinctBy { it.merchant.trim().lowercase() }.take(MAX_EACH).map { it.copy(merchant = it.merchant.trim()) }

        return CheckedSuggestions(RuleSuggestions(aliases, rules), dropped)
    }

    private val json = Json { ignoreUnknownKeys = true }
}
