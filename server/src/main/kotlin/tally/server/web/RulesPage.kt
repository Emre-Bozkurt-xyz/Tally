package tally.server.web

import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.tr
import tally.core.model.Money

// The dashboard's rules page: try text, apply rules to what's there, and edit rules, aliases
// and categories one row at a time. Each section re-renders after a save; a row with an error
// comes back on its own, keeping what you typed.

fun FlowContent.rulesPage(s: Snapshot, edits: RulesEdits, toml: String) {
    h1 { +"Rules" }
    p("muted") {
        +"Aliases turn bank text into a merchant name; rules file merchants into categories, first match wins. "
        +"Rules marked ask suggest instead of filing (good for people). Changes reach your phone on its next sync."
    }
    div("panel") {
        h2 { +"Try it" }
        form(classes = "inline-form") {
            attributes["hx-post"] = "/rules/try"
            attributes["hx-target"] = "#try-result"
            input(InputType.text, name = "raw") {
                placeholder = "Bank text, e.g. DOORDASHMAGLICI"
                attributes["aria-label"] = "Bank text"
                required = true
            }
            input(InputType.text, name = "amount", classes = "amount") {
                placeholder = "Amount"
                attributes["aria-label"] = "Amount"
            }
            button(classes = "quiet") { +"Try" }
        }
        div { id = "try-result" }
    }
    div("panel") {
        h2 { +"Apply to existing transactions" }
        p("muted") { +"Runs your rules over what's waiting in the inbox or has no category. Anything you filed yourself stays as it is." }
        button(classes = "quiet") {
            attributes["hx-post"] = "/rules/replay/preview"
            attributes["hx-target"] = "#replay"
            +"Check what would change"
        }
        div { id = "replay" }
    }
    rulesSection(s, edits)
    aliasesSection(s, edits)
    categoriesSection(s)
    details("panel") {
        summary { +"Advanced: the whole file as TOML" }
        p("muted") { +"The same file as the phone's TOML editor. Saving replaces all categories, aliases and rules with what's in it." }
        form(classes = "toml") {
            textArea {
                name = "toml"
                attributes["spellcheck"] = "false"
                attributes["aria-label"] = "Rules TOML"
                +toml
            }
            div("actions") {
                button(classes = "quiet") {
                    attributes["hx-post"] = "/rules/check"
                    attributes["hx-target"] = "#toml-result"
                    +"Check"
                }
                button(classes = "primary") {
                    attributes["hx-post"] = "/rules/save"
                    attributes["hx-target"] = "#toml-result"
                    +"Save"
                }
                div { id = "toml-result" }
            }
        }
    }
}

// ---------- Rules ----------

fun rulesSectionHtml(s: Snapshot, edits: RulesEdits) = createHTML().div { rulesSection(s, edits) }.let(::unwrap)

private fun FlowContent.rulesSection(s: Snapshot, edits: RulesEdits) {
    div("panel editor") {
        id = "rules-editor"
        h2 { +"Rules" }
        val rules = with(edits) { s.activeRules }
        if (rules.isEmpty()) p("muted") { +"No rules yet. Add one below, or file something in the inbox with \"always\"." }
        div("row-head rule-grid") {
            span { +"Merchant (regex)" }; span { +"Min" }; span { +"Max" }; span { +"Account" }
            span { +"Category" }; span { +"Tags" }; span { +"Ask" }; span { }
        }
        rules.forEachIndexed { i, rule ->
            ruleRow(
                s,
                RulesEdits.RuleForm(
                    rule.id, rule.merchantPattern, rule.minAmountCents?.let(::plain).orEmpty(), rule.maxAmountCents?.let(::plain).orEmpty(),
                    rule.accountId, rule.categoryId, rule.tagsCsv.orEmpty(), rule.ask,
                ),
                error = null,
                matches = edits.matches(s, rule),
                first = i == 0,
                last = i == rules.lastIndex,
            )
        }
        p("muted add-label") { +"Add a rule" }
        ruleRow(s, RulesEdits.RuleForm(null, "", "", "", null, s.categoryTree.firstOrNull()?.id.orEmpty(), "", false), null, null, true, true)
    }
}

fun ruleRowHtml(s: Snapshot, form: RulesEdits.RuleForm, error: String) =
    createHTML().div { ruleRow(s, form, error, null, first = true, last = true) }.let(::unwrap)

private fun FlowContent.ruleRow(s: Snapshot, f: RulesEdits.RuleForm, error: String?, matches: Int?, first: Boolean, last: Boolean) {
    val isNew = f.id.isNullOrEmpty()
    form(classes = "row rule-grid") {
        attributes["hx-post"] = "/rules/rule/save"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        input(InputType.hidden, name = "id") { value = f.id.orEmpty() }
        input(InputType.text, name = "pattern", classes = "mono") {
            value = f.pattern
            placeholder = "Tim Hortons|Second Cup"
            attributes["aria-label"] = "Merchant pattern"
        }
        input(InputType.text, name = "min", classes = "amount") { value = f.min; attributes["aria-label"] = "Minimum amount" }
        input(InputType.text, name = "max", classes = "amount") { value = f.max; attributes["aria-label"] = "Maximum amount" }
        select {
            name = "account"
            attributes["aria-label"] = "Account"
            option { value = ""; +"Any" }
            s.accounts.forEach { a -> option { value = a.id; selected = a.id == f.accountId; +a.name } }
        }
        categorySelect(s, "category", f.categoryId)
        input(InputType.text, name = "tags") { value = f.tags; placeholder = "tag, tag"; attributes["aria-label"] = "Tags" }
        label("check") {
            input(InputType.checkBox, name = "ask") { value = "true"; checked = f.ask }
            span("sr") { +"Ask" }
        }
        div("row-actions") {
            button(classes = if (isNew) "primary" else "quiet") { +(if (isNew) "Add" else "Save") }
            if (!isNew) {
                moveButtons("/rules/rule/${f.id}", "#rules-editor", first, last)
                deleteButton("/rules/rule/${f.id}/delete", "#rules-editor", "Delete this rule?")
                matches?.let { span("muted count-note") { +"$it past" } }
            }
        }
        error?.let { div("row-error") { +it } }
    }
}

// ---------- Aliases ----------

fun aliasesSectionHtml(s: Snapshot, edits: RulesEdits) = createHTML().div { aliasesSection(s, edits) }.let(::unwrap)

private fun FlowContent.aliasesSection(s: Snapshot, edits: RulesEdits) {
    div("panel editor") {
        id = "aliases-editor"
        h2 { +"Aliases" }
        p("muted") { +"Checked against the raw bank or wallet text; the first match names the merchant. Saving re-files past transactions too." }
        val aliases = with(edits) { s.activeAliases }
        div("row-head alias-grid") { span { +"Bank text (regex)" }; span { +"Becomes" }; span { } }
        aliases.forEachIndexed { i, alias ->
            aliasRow(alias.id, alias.pattern, alias.merchantName, null, edits.matches(s, alias), i == 0, i == aliases.lastIndex)
        }
        p("muted add-label") { +"Add an alias" }
        aliasRow(null, "", "", null, null, true, true)
    }
}

fun aliasRowHtml(id: String?, pattern: String, name: String, error: String) =
    createHTML().div { aliasRow(id, pattern, name, error, null, first = true, last = true) }.let(::unwrap)

private fun FlowContent.aliasRow(id: String?, pattern: String, name: String, error: String?, matches: Int?, first: Boolean, last: Boolean) {
    val isNew = id.isNullOrEmpty()
    form(classes = "row alias-grid") {
        attributes["hx-post"] = "/rules/alias/save"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        input(InputType.hidden, name = "id") { value = id.orEmpty() }
        input(InputType.text, name = "pattern", classes = "mono") {
            value = pattern
            placeholder = "(?i)doordash"
            attributes["aria-label"] = "Bank text pattern"
        }
        input(InputType.text, name = "name") { value = name; placeholder = "DoorDash"; attributes["aria-label"] = "Merchant name" }
        div("row-actions") {
            button(classes = if (isNew) "primary" else "quiet") { +(if (isNew) "Add" else "Save") }
            if (!isNew) {
                moveButtons("/rules/alias/$id", "#aliases-editor", first, last)
                deleteButton("/rules/alias/$id/delete", "#aliases-editor", "Delete this alias?")
                matches?.let { span("muted count-note") { +"$it past" } }
            }
        }
        error?.let { div("row-error") { +it } }
    }
}

// ---------- Categories ----------

fun categoriesSectionHtml(s: Snapshot, error: String? = null) = createHTML().div { categoriesSection(s, error) }.let(::unwrap)

private fun FlowContent.categoriesSection(s: Snapshot, error: String? = null) {
    div("panel editor") {
        id = "categories-editor"
        h2 { +"Categories" }
        error?.let { div("result error") { +it } }
        p("muted") {
            +"Two levels. Money in filed under the top-level category named Income counts as income; "
            +"anywhere else it's money back for that spending. Deleting keeps the name on past transactions."
        }
        div("row-head category-grid") { span { +"Name" }; span { +"Under" }; span { +"Monthly budget" }; span { +"Pinned" }; span { } }
        val tree = s.categoryTree
        tree.forEach { c ->
            val siblings = tree.filter { it.parentId == c.parentId }
            categoryRow(s, c.id, c.name, c.parentId, c.monthlyBudgetCents?.let(::plain).orEmpty(), c.pinned, null, siblings.first() == c, siblings.last() == c)
        }
        p("muted add-label") { +"Add a category" }
        form(classes = "row category-grid") {
            attributes["hx-post"] = "/rules/category/add"
            attributes["hx-target"] = "this"
            attributes["hx-swap"] = "outerHTML"
            input(InputType.text, name = "name") { placeholder = "Name"; attributes["aria-label"] = "New category name" }
            parentSelect(s, null, editing = null)
            span { }
            span { }
            div("row-actions") { button(classes = "primary") { +"Add" } }
        }
    }
}

fun categoryRowHtml(s: Snapshot, id: String, name: String, parentId: String?, budget: String, pinned: Boolean, error: String) =
    createHTML().div { categoryRow(s, id, name, parentId, budget, pinned, error, first = true, last = true) }.let(::unwrap)

fun categoryAddErrorHtml(s: Snapshot, name: String, parentId: String?, error: String) = createHTML().form(classes = "row category-grid") {
    attributes["hx-post"] = "/rules/category/add"
    attributes["hx-target"] = "this"
    attributes["hx-swap"] = "outerHTML"
    input(InputType.text, name = "name") { value = name; attributes["aria-label"] = "New category name" }
    parentSelect(s, parentId, editing = null)
    span { }
    span { }
    div("row-actions") { button(classes = "primary") { +"Add" } }
    div("row-error") { +error }
}

private fun FlowContent.categoryRow(
    s: Snapshot, id: String, name: String, parentId: String?, budget: String, pinned: Boolean, error: String?, first: Boolean, last: Boolean,
) {
    form(classes = "row category-grid${if (parentId != null) " child" else ""}") {
        attributes["hx-post"] = "/rules/category/$id/save"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        input(InputType.text, name = "name") { value = name; attributes["aria-label"] = "Category name" }
        parentSelect(s, parentId, editing = id)
        input(InputType.text, name = "budget", classes = "amount") { value = budget; placeholder = "none"; attributes["aria-label"] = "Monthly budget" }
        label("check") {
            input(InputType.checkBox, name = "pinned") { value = "true"; checked = pinned }
            span("sr") { +"Pinned" }
        }
        div("row-actions") {
            button(classes = "quiet") { +"Save" }
            moveButtons("/rules/category/$id", "#categories-editor", first, last)
            deleteButton("/rules/category/$id/delete", "#categories-editor", "Delete $name? Past transactions keep the name.")
        }
        error?.let { div("row-error") { +it } }
    }
}

private fun FlowContent.parentSelect(s: Snapshot, parentId: String?, editing: String?) {
    select {
        name = "parent"
        attributes["aria-label"] = "Parent category"
        option { value = ""; selected = parentId == null; +"(top level)" }
        s.categoryTree.filter { it.parentId == null && it.id != editing }.forEach { p ->
            option { value = p.id; selected = p.id == parentId; +p.name }
        }
    }
}

// ---------- Try it, apply to existing ----------

fun trialHtml(s: Snapshot, trial: RulesEdits.Trial) = createHTML().div("result") {
    div {
        +"Becomes "
        span("strong") { +trial.cleaned }
        trial.alias?.let { span("muted") { +" (alias ${it.pattern})" } } ?: span("muted") { +" (no alias; general cleanup)" }
    }
    div {
        val rule = trial.rule
        when {
            rule != null -> {
                +"${if (rule.ask) "Suggests" else "Files under"} ${s.label(rule.categoryId)}"
                span("muted") { +" (rule ${rule.merchantPattern})" }
            }
            trial.memory != null -> {
                +"No rule; merchant memory suggests ${s.label(trial.memory.lastCategoryId)}"
                span("muted") { +" (${trial.memory.confirmCount}× before)" }
            }
            else -> +"No rule or memory: it goes to the inbox (the LLM may suggest a category)."
        }
    }
}

fun replayHtml(s: Snapshot, changes: List<tally.core.rules.ReplayChange>) = createHTML().div {
    if (changes.isEmpty()) {
        p("muted") { +"Nothing to change: your rules don't match anything waiting or uncategorized." }
        return@div
    }
    val filed = changes.count { it.confirm }
    p { +"$filed would be filed and ${changes.size - filed} suggested (ask rules):" }
    table("compact") {
        tbody {
            changes.take(200).forEach { change ->
                val t = s.transactions.find { it.id == change.id } ?: return@forEach
                tr {
                    td("nowrap") { +s.date(t).format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) }
                    td { +s.merchantName(t) }
                    td("muted") { +"${if (t.categoryId != null) s.label(t.categoryId) else "none"} → " }
                    td { +s.label(change.categoryId) }
                    td("muted") { +(if (change.confirm) "filed" else "suggested") }
                    td("num") { +Money.format(t.amountCents) }
                }
            }
        }
    }
    button(classes = "primary") {
        attributes["hx-post"] = "/rules/replay/apply"
        attributes["hx-target"] = "#replay"
        +"Apply ${changes.size} changes"
    }
}

fun replayDoneHtml(count: Int) = createHTML().div("result ok") {
    +"Updated $count transactions. "
    a(href = "/inbox") { +"Open the inbox" }
}

// ---------- Shared bits ----------

private fun FlowContent.categorySelect(s: Snapshot, name: String, selectedId: String?) {
    select {
        this.name = name
        attributes["aria-label"] = "Category"
        s.categoryTree.forEach { c ->
            option {
                value = c.id
                selected = c.id == selectedId
                +(if (c.parentId == null) c.name else "${s.categoriesById[c.parentId]?.name} › ${c.name}")
            }
        }
    }
}

private fun FlowContent.moveButtons(base: String, target: String, first: Boolean, last: Boolean) {
    button(classes = "icon") {
        attributes["type"] = "button"
        attributes["hx-post"] = "$base/up"
        attributes["hx-target"] = target
        attributes["hx-swap"] = "outerHTML"
        attributes["aria-label"] = "Move up"
        if (first) attributes["disabled"] = "disabled"
        +"↑"
    }
    button(classes = "icon") {
        attributes["type"] = "button"
        attributes["hx-post"] = "$base/down"
        attributes["hx-target"] = target
        attributes["hx-swap"] = "outerHTML"
        attributes["aria-label"] = "Move down"
        if (last) attributes["disabled"] = "disabled"
        +"↓"
    }
}

private fun FlowContent.deleteButton(path: String, target: String, confirm: String) {
    button(classes = "icon danger") {
        attributes["type"] = "button"
        attributes["hx-post"] = path
        attributes["hx-target"] = target
        attributes["hx-swap"] = "outerHTML"
        attributes["hx-confirm"] = confirm
        attributes["aria-label"] = "Delete"
        +"×"
    }
}

/** "15" or "15.50": an amount as you'd type it. */
private fun plain(cents: Long) = if (cents % 100 == 0L) "${cents / 100}" else "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

/** createHTML needs a root; the fragments are its single child. */
private fun unwrap(html: String) = html.trim().removePrefix("<div>").removeSuffix("</div>").trim()

const val RULES_CSS = """
.editor { margin: 1rem 0; }
.row { display:grid; gap:.4rem; align-items:center; padding:.35rem 0; border-bottom:1px solid var(--line); }
.row-head { display:grid; gap:.4rem; color:var(--muted); font-size:.8rem; padding-bottom:.2rem; border-bottom:1px solid var(--line); }
.rule-grid { grid-template-columns: minmax(10rem, 2fr) 4.5rem 4.5rem minmax(6rem, 1fr) minmax(9rem, 1.4fr) minmax(6rem, 1fr) 2.5rem auto; }
.alias-grid { grid-template-columns: minmax(12rem, 2fr) minmax(8rem, 1fr) auto; }
.category-grid { grid-template-columns: minmax(8rem, 1.5fr) minmax(8rem, 1fr) 7rem 3.5rem auto; }
.category-grid.child input[name=name] { margin-left:1.25rem; width:calc(100% - 1.25rem); }
.row input, .row select { width:100%; }
.row input.amount { text-align:right; }
.row-actions { display:flex; gap:.25rem; align-items:center; white-space:nowrap; }
.row-error { grid-column: 1 / -1; color:var(--over); font-size:.88rem; }
.check { display:flex; justify-content:center; }
.check input { width:auto; }
button.icon { padding:.2rem .5rem; color:var(--muted); }
button.icon.danger:hover { color:var(--over); }
button[disabled] { opacity:.35; cursor:default; }
.count-note { font-size:.8rem; margin-left:.25rem; }
.add-label { margin:.9rem 0 0; font-size:.85rem; }
.inline-form { display:flex; gap:.5rem; flex-wrap:wrap; }
.inline-form input[name=raw] { flex:1; min-width:14rem; }
.inline-form input.amount { width:6rem; }
.strong { font-weight:600; }
.sr { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0); }
details.panel summary { cursor:pointer; font-weight:600; }
@media (max-width: 900px) {
  .rule-grid, .alias-grid, .category-grid { grid-template-columns: 1fr 1fr; }
  .row-head { display:none; }
  .row input.mono, .row-actions { grid-column: 1 / -1; }
}
"""
