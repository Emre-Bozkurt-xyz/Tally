package tally.core.rules

import tally.core.normalize.Alias

/** A top-level category and its subcategories, in order. */
data class CategorySpec(val name: String, val children: List<String>)

data class AliasSpec(val match: String, val name: String) {
    fun toAlias() = Alias(Regex(match), name)
}

data class RuleSpec(
    val merchant: String,
    /** "Parent" or "Parent/Child". */
    val category: String,
    val minAmountCents: Long? = null,
    val maxAmountCents: Long? = null,
    /** Account name, e.g. "RBC Visa". */
    val account: String? = null,
    val tags: List<String> = emptyList(),
    /** `ask = true`: suggest [category] in the inbox instead of filing it. */
    val ask: Boolean = false,
)

/** Everything you can edit as TOML. Rule order is priority. */
data class RulesDocument(
    val categories: List<CategorySpec>,
    val aliases: List<AliasSpec>,
    val rules: List<RuleSpec>,
)

data class TomlError(val line: Int, val message: String) {
    override fun toString() = "Line $line: $message"
}

sealed interface TomlImport {
    data class Ok(val document: RulesDocument) : TomlImport

    data class Invalid(val errors: List<TomlError>) : TomlImport
}

/**
 * Reads and writes the rules file:
 *
 * ```toml
 * [categories]
 * Food = ["Groceries", "Coffee"]
 * Health = []
 *
 * [[alias]]
 * match = '(?i)tim hortons.*'
 * name = "Tim Hortons"
 *
 * [[rule]]
 * merchant = "Uber"
 * max_amount = 15.00
 * category = "Transport/Rideshare"
 * account = "RBC Visa"
 * tags = ["rideshare"]
 * ```
 *
 * Supports the subset of TOML the file needs: comments, `[table]`, `[[array of tables]]`, bare
 * or quoted keys, basic and literal strings, numbers, booleans, and (multi-line) arrays.
 */
object RulesToml {
    fun export(document: RulesDocument): String = buildString {
        appendLine("# Tally rules: categories, aliases (raw merchant regex -> name), and rules.")
        appendLine("# Rules run top to bottom; the first match wins. Merchant patterns are")
        appendLine("# case-insensitive regexes matched against the whole cleaned-up merchant name.")
        appendLine()
        appendLine("[categories]")
        for (category in document.categories) {
            appendLine("${key(category.name)} = ${array(category.children)}")
        }
        for (alias in document.aliases) {
            appendLine()
            appendLine("[[alias]]")
            appendLine("match = ${string(alias.match)}")
            appendLine("name = ${string(alias.name)}")
        }
        for (rule in document.rules) {
            appendLine()
            appendLine("[[rule]]")
            appendLine("merchant = ${string(rule.merchant)}")
            rule.minAmountCents?.let { appendLine("min_amount = ${amount(it)}") }
            rule.maxAmountCents?.let { appendLine("max_amount = ${amount(it)}") }
            rule.account?.let { appendLine("account = ${string(it)}") }
            appendLine("category = ${string(rule.category)}")
            if (rule.tags.isNotEmpty()) appendLine("tags = ${array(rule.tags)}")
            if (rule.ask) appendLine("ask = true")
        }
    }

    /** Parses and validates. [accountNames] are the accounts rules may name. */
    fun import(text: String, accountNames: Set<String>): TomlImport {
        val entries = try {
            TomlParser(text).parse()
        } catch (e: TomlSyntaxError) {
            return TomlImport.Invalid(listOf(TomlError(e.line, e.message.orEmpty())))
        }
        return Builder(accountNames).build(entries)
    }

    // --- Writing ---

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

    private fun key(name: String) = if (BARE_KEY.matches(name)) name else basicString(name)

    /** Literal strings keep regexes readable; basic strings when a literal can't hold the value. */
    private fun string(value: String) =
        if ('\\' in value && '\'' !in value && '\n' !in value) "'$value'" else basicString(value)

    private fun basicString(value: String) = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun array(values: List<String>) = values.joinToString(", ", "[", "]") { string(it) }

    private fun amount(cents: Long) = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    // --- Validation ---

    private class Builder(private val accountNames: Set<String>) {
        private val errors = mutableListOf<TomlError>()
        private val categories = mutableListOf<CategorySpec>()
        private val aliases = mutableListOf<Pair<Int, MutableMap<String, Pair<Int, TomlValue>>>>()
        private val rules = mutableListOf<Pair<Int, MutableMap<String, Pair<Int, TomlValue>>>>()
        private var sawCategories = false

        fun build(entries: List<TomlEntry>): TomlImport {
            var section: String? = null
            for (entry in entries) {
                when (entry) {
                    is TomlEntry.Header -> section = header(entry)
                    is TomlEntry.KeyValue -> keyValue(section, entry)
                }
            }
            if (!sawCategories) errors += TomlError(1, "Missing [categories] section")

            val paths = categories.flatMap { parent -> listOf(parent.name) + parent.children.map { "${parent.name}/$it" } }.toSet()
            // Keep each spec's line: invalid aliases drop out, so positions don't line up.
            val aliasesWithLines = aliases.mapNotNull { (line, fields) -> alias(line, fields)?.let { line to it } }
            val seenMatches = mutableSetOf<String>()
            for ((line, spec) in aliasesWithLines) {
                if (!seenMatches.add(spec.match)) errors += TomlError(line, "Duplicate alias for ${spec.match}")
            }
            val aliasSpecs = aliasesWithLines.map { it.second }
            val ruleSpecs = rules.mapNotNull { (line, fields) -> rule(line, fields, paths) }

            return if (errors.isEmpty()) {
                TomlImport.Ok(RulesDocument(categories, aliasSpecs, ruleSpecs))
            } else {
                TomlImport.Invalid(errors.sortedBy { it.line })
            }
        }

        private fun header(entry: TomlEntry.Header): String? = when {
            !entry.isArray && entry.name == "categories" -> {
                if (sawCategories) errors += TomlError(entry.line, "[categories] appears twice")
                sawCategories = true
                "categories"
            }
            entry.isArray && entry.name == "alias" -> "alias".also { aliases += entry.line to mutableMapOf() }
            entry.isArray && entry.name == "rule" -> "rule".also { rules += entry.line to mutableMapOf() }
            else -> {
                errors += TomlError(entry.line, "Unknown section ${if (entry.isArray) "[[${entry.name}]]" else "[${entry.name}]"}")
                null
            }
        }

        private fun keyValue(section: String?, entry: TomlEntry.KeyValue) {
            when (section) {
                "categories" -> category(entry)
                "alias" -> field(aliases.last().second, entry, setOf("match", "name"))
                "rule" -> field(rules.last().second, entry, setOf("merchant", "category", "min_amount", "max_amount", "account", "tags", "ask"))
                null -> errors += TomlError(entry.line, "${entry.key} is outside any section")
            }
        }

        private fun field(fields: MutableMap<String, Pair<Int, TomlValue>>, entry: TomlEntry.KeyValue, allowed: Set<String>) {
            when {
                entry.key !in allowed -> errors += TomlError(entry.line, "Unknown key ${entry.key}")
                entry.key in fields -> errors += TomlError(entry.line, "${entry.key} appears twice")
                else -> fields[entry.key] = entry.line to entry.value
            }
        }

        private fun category(entry: TomlEntry.KeyValue) {
            val children = stringList(entry.line, entry.key, entry.value) ?: return
            val names = listOf(entry.key) + children
            names.filter { '/' in it || it.isBlank() }.forEach {
                errors += TomlError(entry.line, "Category name \"$it\" is empty or contains /")
            }
            if (categories.any { it.name.equals(entry.key, ignoreCase = true) }) {
                errors += TomlError(entry.line, "Category ${entry.key} appears twice")
            }
            children.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys.forEach {
                errors += TomlError(entry.line, "Subcategory $it appears twice under ${entry.key}")
            }
            categories += CategorySpec(entry.key, children)
        }

        private fun alias(line: Int, fields: Map<String, Pair<Int, TomlValue>>): AliasSpec? {
            val match = requiredString(line, fields, "match") ?: return null
            val name = requiredString(line, fields, "name") ?: return null
            Rule.patternError(match)?.let {
                errors += TomlError(fields.getValue("match").first, it)
                return null
            }
            return AliasSpec(match, name)
        }

        private fun rule(line: Int, fields: Map<String, Pair<Int, TomlValue>>, paths: Set<String>): RuleSpec? {
            val merchant = requiredString(line, fields, "merchant")
            val category = requiredString(line, fields, "category")
            val min = optionalAmount(fields, "min_amount")
            val max = optionalAmount(fields, "max_amount")
            val account = fields["account"]?.let { (valueLine, value) ->
                (value as? TomlValue.Str)?.value.also {
                    when {
                        it == null -> errors += TomlError(valueLine, "account must be a string")
                        it !in accountNames -> errors += TomlError(valueLine, "Unknown account \"$it\" (known: ${accountNames.sorted().joinToString()})")
                    }
                }
            }
            val tags = fields["tags"]?.let { (valueLine, value) ->
                stringList(valueLine, "tags", value)?.also { list ->
                    list.filter { ',' in it || it.isBlank() }.forEach { errors += TomlError(valueLine, "Tag \"$it\" is empty or contains a comma") }
                }
            }.orEmpty()

            val ask = fields["ask"]?.let { (valueLine, value) ->
                (value as? TomlValue.Bool)?.value ?: run {
                    errors += TomlError(valueLine, "ask must be true or false")
                    null
                }
            } ?: false

            merchant?.let { pattern -> Rule.patternError(pattern)?.let { errors += TomlError(fields.getValue("merchant").first, it) } }
            if (category != null && category !in paths) {
                errors += TomlError(fields.getValue("category").first, "Unknown category \"$category\"")
            }
            if (min != null && max != null && min > max) errors += TomlError(line, "min_amount is above max_amount")
            if (merchant == null || category == null) return null
            return RuleSpec(merchant, category, min, max, account, tags, ask)
        }

        private fun requiredString(line: Int, fields: Map<String, Pair<Int, TomlValue>>, key: String): String? {
            val (valueLine, value) = fields[key] ?: run {
                errors += TomlError(line, "Missing $key")
                return null
            }
            return (value as? TomlValue.Str)?.value ?: run {
                errors += TomlError(valueLine, "$key must be a string")
                null
            }
        }

        private fun optionalAmount(fields: Map<String, Pair<Int, TomlValue>>, key: String): Long? {
            val (line, value) = fields[key] ?: return null
            val raw = (value as? TomlValue.Num)?.raw ?: run {
                errors += TomlError(line, "$key must be a number like 15.00")
                return null
            }
            val match = AMOUNT.matchEntire(raw) ?: run {
                errors += TomlError(line, "$key must be a non-negative amount with at most 2 decimals")
                return null
            }
            val dollars = match.groupValues[1].replace("_", "").toLong()
            val cents = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
            return dollars * 100 + cents
        }

        private fun stringList(line: Int, key: String, value: TomlValue): List<String>? {
            val items = (value as? TomlValue.Arr)?.items
            if (items == null || items.any { it !is TomlValue.Str }) {
                errors += TomlError(line, "$key must be a list of strings")
                return null
            }
            return items.map { (it as TomlValue.Str).value }
        }

        companion object {
            val AMOUNT = Regex("([0-9][0-9_]*)(?:\\.([0-9]{1,2}))?")
        }
    }
}

// --- Parsing (TOML subset) ---

internal sealed interface TomlValue {
    data class Str(val value: String) : TomlValue

    data class Num(val raw: String) : TomlValue

    data class Bool(val value: Boolean) : TomlValue

    data class Arr(val items: List<TomlValue>) : TomlValue
}

internal sealed interface TomlEntry {
    val line: Int

    data class Header(val name: String, val isArray: Boolean, override val line: Int) : TomlEntry

    data class KeyValue(val key: String, val value: TomlValue, override val line: Int) : TomlEntry
}

internal class TomlSyntaxError(val line: Int, message: String) : Exception(message)

internal class TomlParser(private val text: String) {
    private var pos = 0
    private var line = 1

    fun parse(): List<TomlEntry> {
        val entries = mutableListOf<TomlEntry>()
        while (true) {
            skipBlankLines()
            if (atEnd()) return entries
            entries += if (peek() == '[') header() else keyValue()
            endOfLine()
        }
    }

    private fun header(): TomlEntry.Header {
        val startLine = line
        pos++ // [
        val isArray = peek() == '['
        if (isArray) pos++
        skipSpaces()
        val name = bareKey()
        skipSpaces()
        expect(']')
        if (isArray) expect(']')
        return TomlEntry.Header(name, isArray, startLine)
    }

    private fun keyValue(): TomlEntry.KeyValue {
        val startLine = line
        val key = when (peek()) {
            '"' -> basicString()
            '\'' -> literalString()
            else -> bareKey()
        }
        skipSpaces()
        expect('=')
        skipSpaces()
        return TomlEntry.KeyValue(key, value(), startLine)
    }

    private fun value(): TomlValue = when (val c = peek()) {
        '"' -> TomlValue.Str(basicString())
        '\'' -> TomlValue.Str(literalString())
        '[' -> array()
        else -> when {
            text.startsWith("true", pos) -> TomlValue.Bool(true).also { pos += 4 }
            text.startsWith("false", pos) -> TomlValue.Bool(false).also { pos += 5 }
            c != null && (c.isDigit() || c == '-' || c == '+') -> TomlValue.Num(takeWhile { it.isLetterOrDigit() || it in "+-._" })
            else -> throw TomlSyntaxError(line, "Expected a value")
        }
    }

    private fun array(): TomlValue.Arr {
        pos++ // [
        val items = mutableListOf<TomlValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (peek() == ']') {
                pos++
                return TomlValue.Arr(items)
            }
            items += value()
            skipWhitespaceAndComments()
            when (peek()) {
                ',' -> pos++
                ']' -> {}
                else -> throw TomlSyntaxError(line, "Expected , or ] in list")
            }
        }
    }

    private fun basicString(): String {
        pos++ // "
        val out = StringBuilder()
        while (true) {
            val c = peek() ?: throw TomlSyntaxError(line, "Unterminated string")
            pos++
            when (c) {
                '"' -> return out.toString()
                '\n' -> throw TomlSyntaxError(line, "Unterminated string")
                '\\' -> {
                    val e = peek() ?: throw TomlSyntaxError(line, "Unterminated string")
                    pos++
                    when (e) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(pos, minOf(pos + 4, text.length))
                            val code = hex.toIntOrNull(16)
                            if (hex.length < 4 || code == null) throw TomlSyntaxError(line, "Bad \\u escape")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> throw TomlSyntaxError(line, "Unknown escape \\$e (use single quotes for regexes)")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun literalString(): String {
        pos++ // '
        val end = text.indexOf('\'', pos)
        val newline = text.indexOf('\n', pos)
        if (end < 0 || (newline in 0 until end)) throw TomlSyntaxError(line, "Unterminated string")
        return text.substring(pos, end).also { pos = end + 1 }
    }

    private fun bareKey(): String {
        val key = takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (key.isEmpty()) throw TomlSyntaxError(line, "Expected a key")
        return key
    }

    /** After a header or key/value: only spaces and a comment may follow on the line. */
    private fun endOfLine() {
        skipSpaces()
        if (peek() == '#') skipComment()
        when (peek()) {
            null -> {}
            '\r' -> {
                pos++
                if (peek() == '\n') newline()
            }
            '\n' -> newline()
            else -> throw TomlSyntaxError(line, "Unexpected text after value")
        }
    }

    private fun skipBlankLines() = skipWhitespaceAndComments()

    private fun skipWhitespaceAndComments() {
        while (!atEnd()) {
            when (peek()) {
                ' ', '\t', '\r' -> pos++
                '\n' -> newline()
                '#' -> skipComment()
                else -> return
            }
        }
    }

    private fun skipComment() {
        while (!atEnd() && peek() != '\n') pos++
    }

    private fun skipSpaces() {
        while (peek() == ' ' || peek() == '\t') pos++
    }

    private fun newline() {
        pos++
        line++
    }

    private fun expect(c: Char) {
        if (peek() != c) throw TomlSyntaxError(line, "Expected $c")
        pos++
    }

    private fun takeWhile(predicate: (Char) -> Boolean): String {
        val start = pos
        while (!atEnd() && predicate(text[pos])) pos++
        return text.substring(start, pos)
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun atEnd() = pos >= text.length
}
