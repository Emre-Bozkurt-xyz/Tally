package tally.server.web

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.canvas
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.tfoot
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.Money
import tally.core.model.TransactionStatus
import tally.core.report.BudgetStatus
import tally.core.rules.TomlImport
import tally.core.statement.StatementKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Server-rendered pages. HTMX does the inline edits (each POST returns the fragment to swap
// in); Chart.js draws the two charts. Both load pinned versions from jsDelivr.

private const val HTMX = "https://cdn.jsdelivr.net/npm/htmx.org@2.0.4/dist/htmx.min.js"
private const val CHART_JS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"

private val dayFormat = DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.CANADA)
private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.CANADA)

enum class Tab(val path: String, val label: String) {
    OVERVIEW("/", "Overview"), LEDGER("/ledger", "Ledger"), INBOX("/inbox", "Inbox"), RULES("/rules", "Rules"), STATEMENTS("/statements", "Statements"),
}

fun HTML.page(title: String, tab: Tab, s: Snapshot, who: String, charts: Boolean = false, content: FlowContent.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("$title · Tally")
        style { unsafe { +CSS } }
        script(src = HTMX) {}
        if (charts) script(src = CHART_JS) {}
    }
    body {
        // This page's id: its HTMX edits send it back, so it can tell its own changes from others'.
        val source = "web-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        attributes["data-source"] = source
        attributes["data-live"] = if (tab == Tab.RULES || tab == Tab.STATEMENTS) "banner" else "auto"
        attributes["hx-headers"] = """{"X-Tally-Source":"$source"}"""
        header {
            a(href = "/", classes = "brand") { +"Tally" }
            nav {
                Tab.entries.forEach { t ->
                    a(href = t.path, classes = if (t == tab) "active" else null) {
                        +t.label
                        if (t == Tab.INBOX && s.needsReview.isNotEmpty()) span("count") { +"${s.needsReview.size}" }
                    }
                }
            }
            span("who") { +who }
        }
        div("live-banner") {
            attributes["hidden"] = "hidden"
            +"Changed on another device. "
            a(href = "") { +"Refresh" }
        }
        main { content() }
        script { unsafe { +LIVE_JS } }
    }
}

// Reloads when another device syncs (or shows a banner while you're typing, and on Rules).
private const val LIVE_JS = """
(() => {
  const me = document.body.dataset.source, auto = document.body.dataset.live === 'auto';
  const banner = document.querySelector('.live-banner');
  let timer;
  const busy = () => { const el = document.activeElement; return el && ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName); };
  const events = new EventSource('/api/events');
  events.addEventListener('sync', e => {
    if (JSON.parse(e.data).source === me) return;
    if (auto && !busy()) { clearTimeout(timer); timer = setTimeout(() => location.reload(), 700); }
    else banner.hidden = false;
  });
})();
"""

// ---------- Overview ----------

fun FlowContent.overview(o: Overview) {
    val s = o.snapshot
    div("month-nav") {
        a(href = "/?month=${o.month.minusMonths(1)}") { +"‹" }
        h1 { +o.month.format(monthFormat) }
        a(href = "/?month=${o.month.plusMonths(1)}") { +"›" }
    }
    div("cards") {
        card("Spent", Money.format(o.summary.spentCents), "Last month ${Money.format(o.lastMonth.spentCents)}")
        card("Income", Money.format(o.summary.incomeCents), null)
        o.budgetCents?.let { budget ->
            val left = budget - o.summary.spentCents
            card(
                "Budget",
                if (left >= 0) "${Money.format(left)} left" else "${Money.format(-left)} over",
                "of ${Money.format(budget)}",
                o.status(o.summary.spentCents, budget),
            )
        }
        if (s.needsReview.isNotEmpty()) {
            div("card") {
                div("label") { +"To review" }
                div("value") { a(href = "/inbox") { +"${s.needsReview.size}" } }
            }
        }
    }

    div("grid") {
        div("panel") {
            h2 { +"By category" }
            if (o.summary.categories.isEmpty()) p("muted") { +"Nothing spent this month yet." }
            val max = o.summary.categories.maxOfOrNull { maxOf(it.cents, o.budgetOf(it.categoryId) ?: 0) }?.coerceAtLeast(1) ?: 1
            o.summary.categories.forEach { total ->
                val budget = o.budgetOf(total.categoryId)
                val status = o.status(total.cents, budget)
                div("bar-row") {
                    a(href = ledgerLink(o.month, total.categoryId ?: LedgerQuery.UNCATEGORIZED), classes = "bar-label") { +s.label(total.categoryId) }
                    div("bar-track") {
                        div("bar ${status?.name?.lowercase().orEmpty()}") { style = "width:${pct(total.cents, max)}%" }
                        budget?.let { div("budget-mark") { style = "left:${pct(it, max)}%" } }
                    }
                    span("bar-value") {
                        +Money.format(total.cents)
                        budget?.let { span("muted") { +" / ${Money.format(it)}" } }
                    }
                }
                total.children.forEach { child ->
                    div("bar-row child") {
                        a(href = ledgerLink(o.month, child.categoryId ?: LedgerQuery.UNCATEGORIZED), classes = "bar-label") {
                            +(child.categoryId?.let { s.categoriesById[it]?.name } ?: "Other")
                        }
                        div("bar-track") { div("bar") { style = "width:${pct(child.cents, max)}%" } }
                        span("bar-value") { +Money.format(child.cents) }
                    }
                }
            }
        }
        div("panel") {
            h2 { +"Day by day" }
            canvas { id = "cumulative" }
            h2 { +"Last 6 months" }
            canvas { id = "months" }
        }
        div("panel") {
            h2 { +"Top merchants" }
            if (o.topMerchants.isEmpty()) p("muted") { +"No purchases with a merchant yet." }
            table("compact") {
                tbody {
                    o.topMerchants.forEach { m ->
                        tr {
                            td { +(s.merchantsById[m.merchantId]?.name ?: "?") }
                            td("muted num") { +"${m.count}×" }
                            td("num") { +Money.format(m.cents) }
                        }
                    }
                }
            }
        }
    }

    val data = buildJsonObject {
        put("days", JsonArray((1..o.cumulative.size).map { JsonPrimitive(it) }))
        put("thisLabel", o.month.format(shortMonth))
        put("lastLabel", o.month.minusMonths(1).format(shortMonth))
        put("thisMonth", JsonArray(o.cumulative.map { it?.let { c -> JsonPrimitive(c / 100.0) } ?: JsonNull }))
        put("lastMonth", JsonArray(o.cumulativeLast.map { it?.let { c -> JsonPrimitive(c / 100.0) } ?: JsonNull }))
        put("budget", o.budgetCents?.let { JsonPrimitive(it / 100.0) } ?: JsonNull)
        put("months", JsonArray(o.months.map { JsonPrimitive(it.month.format(shortMonth)) }))
        put("spent", JsonArray(o.months.map { JsonPrimitive(it.spentCents / 100.0) }))
        put("income", JsonArray(o.months.map { JsonPrimitive(it.incomeCents / 100.0) }))
    }
    // "</" can't appear in JSON numbers or these labels, so embedding is safe.
    script { unsafe { +"const tally = ${Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), data)};\n$CHARTS_JS" } }
}

private fun FlowContent.card(label: String, value: String, sub: String?, status: BudgetStatus? = null) {
    div("card ${status?.name?.lowercase().orEmpty()}") {
        div("label") { +label }
        div("value") { +value }
        sub?.let { div("sub") { +it } }
    }
}

private fun pct(part: Long, whole: Long) = "%.1f".format(Locale.ROOT, 100.0 * part.coerceAtLeast(0) / whole)

private fun ledgerLink(month: YearMonth, categoryId: String) =
    "/ledger?from=${month.atDay(1)}&to=${month.atEndOfMonth()}&category=$categoryId"

// ---------- Ledger ----------

fun FlowContent.ledger(s: Snapshot, q: LedgerQuery) {
    val rows = q.apply(s)
    form(action = "/ledger", classes = "filters") {
        label { +"From"; input(InputType.date, name = "from") { value = q.from.toString() } }
        label { +"To"; input(InputType.date, name = "to") { value = q.to.toString() } }
        label {
            +"Category"
            select {
                name = "category"
                option { value = ""; +"Any" }
                option { value = LedgerQuery.UNCATEGORIZED; selected = q.categoryId == LedgerQuery.UNCATEGORIZED; +"Uncategorized" }
                s.categoryTree.forEach { c ->
                    option { value = c.id; selected = q.categoryId == c.id; +(if (c.parentId == null) c.name else "  ${c.name}") }
                }
            }
        }
        label {
            +"Account"
            select {
                name = "account"
                option { value = ""; +"Any" }
                s.accounts.forEach { a -> option { value = a.id; selected = q.accountId == a.id; +a.name } }
            }
        }
        if (s.tags.isNotEmpty()) {
            label {
                +"Tag"
                select {
                    name = "tag"
                    option { value = ""; +"Any" }
                    s.tags.forEach { t -> option { value = t.id; selected = q.tagId == t.id; +t.name } }
                }
            }
        }
        label { +"Search"; input(InputType.search, name = "q") { value = q.text.orEmpty(); placeholder = "Merchant or note" } }
        button(classes = "primary") { +"Filter" }
    }
    val shown = rows.take(LEDGER_LIMIT)
    table("ledger") {
        thead { tr { th { +"Date" }; th { +"Merchant" }; th { +"Category" }; th(classes = "hide-sm") { +"Account" }; th(classes = "num") { +"Amount" } } }
        tbody { shown.forEach { ledgerRow(s, it) } }
        tfoot {
            tr {
                td { colSpan = "3"; +("${rows.size} transactions" + if (rows.size > shown.size) " (first $LEDGER_LIMIT shown)" else "") }
                td("hide-sm") {}
                td("num") { +Money.format(rows.sumOf { it.amountCents }) }
            }
        }
    }
}

private const val LEDGER_LIMIT = 500

fun TBODY.ledgerRow(s: Snapshot, t: TransactionRow) {
    tr(if (t.status == TransactionStatus.CAPTURED.name) "captured" else null) {
        td("nowrap") { +s.date(t).format(dayFormat) }
        td {
            +s.merchantName(t)
            t.note?.takeIf { it != s.merchantName(t) }?.let { span("muted note") { +" $it" } }
            s.tagsByTransaction[t.id].orEmpty().forEach { span("tag") { +it.name } }
            if (s.splits[t.id] != null) span("tag") { +"split" }
        }
        td { categorySelect(s, t, "/ledger/${t.id}/category", target = "closest tr") }
        td("hide-sm muted") { +(t.accountId?.let { s.accountsById[it]?.name } ?: "") }
        // Money in shows as +$12.00; money out without a sign.
        td(if (t.amountCents < 0) "num income" else "num") { +(if (t.amountCents < 0) "+${Money.format(-t.amountCents)}" else Money.format(t.amountCents)) }
    }
}

/** A row on its own, for HTMX to swap in after an edit. */
fun ledgerRowHtml(s: Snapshot, t: TransactionRow): String = createHTML().table { tbody { ledgerRow(s, t) } }
    .substringAfter("<tbody>").substringBeforeLast("</tbody>").trim()

private fun FlowContent.categorySelect(s: Snapshot, t: TransactionRow, post: String, target: String) {
    select("category") {
        name = "categoryId"
        attributes["hx-post"] = post
        attributes["hx-trigger"] = "change"
        attributes["hx-target"] = target
        attributes["hx-swap"] = "outerHTML"
        attributes["aria-label"] = "Category"
        if (t.categoryId == null || s.categoryTree.none { it.id == t.categoryId }) {
            option { value = ""; selected = true; +"Pick a category…" }
        }
        s.categoryTree.forEach { c ->
            option {
                value = c.id
                selected = c.id == t.categoryId
                +(if (c.parentId == null) c.name else "${s.categoriesById[c.parentId]?.name} › ${c.name}")
            }
        }
    }
}

// ---------- Inbox ----------

fun FlowContent.inbox(s: Snapshot) {
    h1 { +"Inbox" }
    if (s.needsReview.isEmpty()) {
        p("muted") { +"Nothing to review. Captures that need a look show up here and on your phone." }
        return
    }
    p("muted") { +"Captured purchases waiting for you. Changes reach your phone on its next sync." }
    div("inbox") { s.needsReview.forEach { inboxItem(s, it) } }
}

fun FlowContent.inboxItem(s: Snapshot, t: TransactionRow) {
    div("item") {
        id = "item-${t.id}"
        div("item-main") {
            div("amount") { +Money.format(t.amountCents) }
            div {
                div("merchant") { +s.merchantName(t) }
                div("muted") {
                    +s.date(t).format(dayFormat)
                    t.accountId?.let { s.accountsById[it] }?.let { +" · ${it.name}" }
                    t.merchantRaw?.takeIf { it != s.merchantName(t) }?.let { +" · $it" }
                }
            }
        }
        div("item-actions") {
            val settling = s.settles(t)
            if (settling != null) {
                span("suggestion") {
                    +"Settles ${settling.owedBy}'s ${Money.format(settling.amountCents)}"
                    span("badge") { +"owed to you" }
                }
                button(classes = "primary") {
                    attributes["hx-post"] = "/inbox/${t.id}/settle/${settling.splitId}"
                    attributes["hx-target"] = "#item-${t.id}"
                    attributes["hx-swap"] = "outerHTML"
                    +"Settle"
                }
            } else if (t.categoryId != null) {
                span("suggestion") {
                    +s.label(t.categoryId)
                    if (t.amountCents < 0 && !s.isIncomeCategory(t.categoryId)) span("muted") { +" (money back)" }
                    when (t.categorySource) {
                        CategorySource.RULE.name -> span("badge") { +"your default" }
                        CategorySource.LLM.name -> span("badge") { +"suggested" }
                        CategorySource.MEMORY.name -> span("badge") { +"from memory" }
                    }
                }
                button(classes = "primary") {
                    attributes["hx-post"] = "/inbox/${t.id}/confirm"
                    attributes["hx-target"] = "#item-${t.id}"
                    attributes["hx-swap"] = "outerHTML"
                    +"Confirm"
                }
            }
            categorySelect(s, t, "/inbox/${t.id}/category", target = "#item-${t.id}")
            button(classes = "quiet") {
                attributes["hx-post"] = "/inbox/${t.id}/ignore"
                attributes["hx-target"] = "#item-${t.id}"
                attributes["hx-swap"] = "outerHTML"
                attributes["hx-confirm"] = "Delete this transaction? (Not a purchase.)"
                +"Ignore"
            }
        }
    }
}

fun inboxDoneHtml(message: String): String = createHTML().div("item done") { +message }

// ---------- Statements ----------

fun FlowContent.statements() {
    h1 { +"Statements" }
    p("muted") {
        +"Upload an RBC statement PDF (chequing or credit card). Tally checks it against what it captured: "
        +"matching purchases take the statement's final amount, ones it never saw are added "
        +"(to the inbox unless a rule or merchant memory knows the category), and anything missing from the statement is listed. "
        +"You see all of it before anything is saved. Importing the same statement twice changes nothing."
    }
    form(classes = "upload") {
        attributes["hx-post"] = "/statements/preview"
        attributes["hx-encoding"] = "multipart/form-data"
        attributes["hx-target"] = "#statement"
        input(InputType.file, name = "pdf") {
            accept = "application/pdf"
            required = true
        }
        button(classes = "primary") { +"Read statement" }
    }
    div { id = "statement" }
}

fun statementPreviewHtml(s: Snapshot, token: String, plan: ImportPlan): String = createHTML().div {
    val st = plan.statement
    val kind = if (st.kind == StatementKind.CREDIT_CARD) "Credit card" else "Chequing"
    h2 { +"$kind ••${st.last4}, ${st.from.format(dayFormat)} to ${st.to.format(dayFormat)}, ${st.to.year}" }
    if (st.problems.isNotEmpty()) {
        div("result error") {
            +"This statement doesn't add up the way Tally read it; check the rows before importing:"
            st.problems.forEach { div { +it } }
        }
    }
    form(classes = "filters") {
        label {
            +"Account"
            select {
                name = "account"
                attributes["hx-post"] = "/statements/$token/preview"
                attributes["hx-target"] = "#statement"
                attributes["hx-trigger"] = "change"
                if (plan.accountId == null) option { value = ""; selected = true; +"Pick the account…" }
                s.accounts.forEach { a -> option { value = a.id; selected = a.id == plan.accountId; +a.name } }
            }
        }
        if (plan.accountId != null) {
            button(classes = "primary") {
                attributes["hx-post"] = "/statements/$token/import"
                attributes["hx-target"] = "#statement"
                val updates = plan.matched.count { it.updated != null }
                attributes["hx-confirm"] = "Add ${plan.added.size} new transactions and update $updates matched ones?"
                +"Import"
            }
        }
    }
    div("cards") {
        statCard("New", plan.added.size, "${plan.added.count { it.transaction.status == TransactionStatus.CAPTURED.name }} to review")
        val changed = plan.matched.count { m -> m.updated != null && m.updated.amountCents != m.transaction.amountCents }
        statCard("Matched", plan.matched.size, "$changed amounts change")
        statCard("Missing from statement", plan.missing.size, null)
        if (plan.alreadyImported > 0) statCard("Already imported", plan.alreadyImported, null)
    }
    if (plan.added.isNotEmpty()) {
        h2 { +"New" }
        table("compact") {
            tbody {
                plan.added.forEach { a ->
                    tr(if (a.transaction.status == TransactionStatus.CAPTURED.name) "captured" else null) {
                        td("nowrap") { +a.line.date.format(dayFormat) }
                        td { +(a.merchant?.name ?: a.transaction.merchantId?.let { s.merchantsById[it]?.name } ?: a.line.merchant) }
                        td("muted") {
                            +when {
                                a.transaction.kind != Kind.EXPENSE.name -> a.transaction.kind.lowercase()
                                a.transaction.categoryId != null -> s.label(a.transaction.categoryId)
                                else -> "to review"
                            }
                        }
                        td("num") { +signed(a.line.amountCents) }
                    }
                }
            }
        }
    }
    if (plan.matched.isNotEmpty()) {
        h2 { +"Matched" }
        table("compact") {
            tbody {
                plan.matched.forEach { m ->
                    tr {
                        td("nowrap") { +m.line.date.format(dayFormat) }
                        td { +m.line.merchant }
                        td("muted") { +"${s.merchantName(m.transaction)} · ${s.date(m.transaction).format(dayFormat)}" }
                        td("num") {
                            if (m.line.amountCents != m.transaction.amountCents) span("muted") { +"${signed(m.transaction.amountCents)} → " }
                            +signed(m.line.amountCents)
                        }
                    }
                }
            }
        }
    }
    if (plan.missing.isNotEmpty()) {
        h2 { +"In Tally, not on the statement" }
        p("muted") { +"Maybe a different account, a duplicate, or a purchase that was cancelled. Nothing changes for these." }
        table("compact") {
            tbody {
                plan.missing.forEach { t ->
                    tr {
                        td("nowrap") { +s.date(t).format(dayFormat) }
                        td { +s.merchantName(t) }
                        td("num") { +signed(t.amountCents) }
                    }
                }
            }
        }
    }
}

fun statementImportedHtml(plan: ImportPlan?): String = createHTML().div {
    if (plan == null) {
        classes = setOf("result", "error")
        +"That statement expired or the account is gone; upload it again."
        return@div
    }
    classes = setOf("result", "ok")
    val review = plan.added.count { it.transaction.status == TransactionStatus.CAPTURED.name }
    +"Imported: ${plan.added.size} new ($review in the inbox), ${plan.matched.count { it.updated != null }} matched transactions updated."
    if (review > 0) {
        +" "
        a(href = "/inbox") { +"Open the inbox" }
    }
}

fun statementErrorHtml(message: String): String = createHTML().div("result error") { +message }

private fun FlowContent.statCard(label: String, count: Int, sub: String?) {
    div("card") {
        div("label") { +label }
        div("value") { +"$count" }
        sub?.let { div("sub") { +it } }
    }
}

private fun signed(cents: Long) = if (cents < 0) "+${Money.format(-cents)}" else Money.format(cents)

// ---------- Rules ----------

fun FlowContent.rules(s: Snapshot, toml: String) {
    h1 { +"Rules" }
    p("muted") {
        +"Categories, aliases and rules as one file, the same one the phone's editor shows. "
        +"Saving changes them everywhere on the next sync; old transactions keep their categories."
    }
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
    h2 { +"Rules in order" }
    val active = s.rules.filter { !it.deleted }.sortedBy { it.priority }
    if (active.isEmpty()) p("muted") { +"No rules yet." }
    table("compact") {
        tbody {
            active.forEach { r ->
                tr {
                    td { span("mono") { +r.merchantPattern } }
                    td("muted") {
                        +listOfNotNull(
                            r.minAmountCents?.let { "≥ ${Money.format(it)}" },
                            r.maxAmountCents?.let { "≤ ${Money.format(it)}" },
                            r.accountId?.let { s.accountsById[it]?.name },
                        ).joinToString(" · ")
                    }
                    td { +s.label(r.categoryId) }
                    td { r.tagsCsv?.split(',')?.filter { it.isNotBlank() }?.forEach { span("tag") { +it.trim() } } }
                }
            }
        }
    }
}

fun tomlResultHtml(result: TomlImport, saved: Boolean): String = createHTML().div {
    when (result) {
        is TomlImport.Ok -> {
            classes = setOf("result", "ok")
            val d = result.document
            +"${if (saved) "Saved" else "Looks good"}: ${d.categories.size} categories, ${d.aliases.size} aliases, ${d.rules.size} rules."
        }
        is TomlImport.Invalid -> {
            classes = setOf("result", "error")
            +"Not saved:"
            result.errors.forEach { div { +it.toString() } }
        }
    }
}

private const val CHARTS_JS = """
(() => {
  const css = getComputedStyle(document.documentElement);
  const fg = css.getPropertyValue('--muted').trim(), accent = css.getPropertyValue('--accent').trim(),
        soft = css.getPropertyValue('--soft').trim(), good = css.getPropertyValue('--good').trim();
  Chart.defaults.color = fg;
  Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
  const money = v => '$' + Number(v).toLocaleString(undefined, {maximumFractionDigits: 0});
  const datasets = [
    {label: tally.thisLabel, data: tally.thisMonth, borderColor: accent, backgroundColor: accent, tension: .2, pointRadius: 0, spanGaps: false},
    {label: tally.lastLabel, data: tally.lastMonth, borderColor: soft, backgroundColor: soft, borderDash: [4, 4], tension: .2, pointRadius: 0},
  ];
  if (tally.budget !== null) datasets.push({label: 'Budget', data: tally.days.map(() => tally.budget), borderColor: fg, borderWidth: 1, pointRadius: 0});
  new Chart(document.getElementById('cumulative'), {
    type: 'line', data: {labels: tally.days, datasets},
    options: {interaction: {mode: 'index', intersect: false}, scales: {y: {ticks: {callback: money}}},
              plugins: {tooltip: {callbacks: {label: c => c.dataset.label + ': ' + money(c.parsed.y)}}}},
  });
  new Chart(document.getElementById('months'), {
    type: 'bar',
    data: {labels: tally.months, datasets: [
      {label: 'Spent', data: tally.spent, backgroundColor: accent},
      {label: 'Income', data: tally.income, backgroundColor: good},
    ]},
    options: {scales: {y: {ticks: {callback: money}}},
              plugins: {tooltip: {callbacks: {label: c => c.dataset.label + ': ' + money(c.parsed.y)}}}},
  });
})();
"""

private const val CSS = """
:root { --bg:#f7f7f5; --panel:#fff; --fg:#1d1d1f; --muted:#6b6b70; --line:#e4e4e0; --accent:#2f6fde; --soft:#a9b8d6;
        --good:#2e9e6a; --warn:#d9922b; --over:#d2483d; --tag:#eef1f6; color-scheme: light; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#141416; --panel:#1d1d20; --fg:#ececee; --muted:#9a9aa2; --line:#2e2e33; --accent:#6c9cf5; --soft:#4b5a78;
          --good:#4cc08a; --warn:#e8a94c; --over:#f0675c; --tag:#2a2e38; color-scheme: dark; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg); font: 15px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
a { color: var(--accent); text-decoration: none; }
header { display:flex; align-items:center; gap:1.5rem; padding:.75rem 1.25rem; background:var(--panel); border-bottom:1px solid var(--line);
         position:sticky; top:0; z-index:1; flex-wrap:wrap; }
.brand { font-weight:700; color:var(--fg); font-size:1.1rem; }
nav { display:flex; gap:.25rem; flex-wrap:wrap; }
nav a { color:var(--muted); padding:.3rem .65rem; border-radius:6px; }
nav a.active { color:var(--fg); background:var(--tag); }
.count { background:var(--accent); color:#fff; border-radius:9px; padding:0 .4rem; margin-left:.35rem; font-size:.8rem; }
.live-banner { position:fixed; bottom:1rem; left:50%; transform:translateX(-50%); background:var(--fg); color:var(--bg);
                padding:.5rem 1rem; border-radius:8px; z-index:2; box-shadow:0 4px 14px rgba(0,0,0,.2); }
.live-banner a { color:var(--bg); text-decoration:underline; }
.who { margin-left:auto; color:var(--muted); font-size:.85rem; }
main { max-width:1200px; margin:0 auto; padding:1.25rem 1rem 3rem; }
h1 { font-size:1.4rem; margin:.25rem 0 1rem; }
h2 { font-size:1rem; margin:0 0 .75rem; }
.muted { color:var(--muted); }
.month-nav { display:flex; align-items:center; gap:1rem; }
.month-nav a { font-size:1.6rem; padding:0 .4rem; }
.month-nav h1 { margin:.25rem 0; }
.cards { display:grid; grid-template-columns:repeat(auto-fit, minmax(170px, 1fr)); gap:.75rem; margin:1rem 0; }
.card, .panel { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:.9rem 1rem; }
.card .label { color:var(--muted); font-size:.85rem; }
.card .value { font-size:1.45rem; font-weight:600; font-variant-numeric:tabular-nums; }
.card .sub { color:var(--muted); font-size:.85rem; }
.card.warning .value { color:var(--warn); } .card.over .value { color:var(--over); }
.grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(340px, 1fr)); gap:.75rem; }
.grid canvas { margin-bottom:1.25rem; }
.bar-row { display:grid; grid-template-columns:9rem 1fr 8.5rem; align-items:center; gap:.6rem; margin:.35rem 0; }
.bar-row.child { font-size:.88rem; } .bar-row.child .bar-label { padding-left:.9rem; color:var(--muted); }
.bar-label { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--fg); }
.bar-track { position:relative; height:.7rem; background:var(--tag); border-radius:4px; }
.bar { height:100%; background:var(--accent); border-radius:4px; }
.child .bar { background:var(--soft); }
.bar.warning { background:var(--warn); } .bar.over { background:var(--over); }
.budget-mark { position:absolute; top:-3px; bottom:-3px; width:2px; background:var(--fg); opacity:.55; }
.bar-value, .num { text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }
table { width:100%; border-collapse:collapse; }
th { text-align:left; color:var(--muted); font-weight:500; font-size:.85rem; }
th, td { padding:.45rem .5rem; border-bottom:1px solid var(--line); vertical-align:middle; }
table.ledger { background:var(--panel); border:1px solid var(--line); border-radius:10px; overflow:hidden; }
tr.captured td:first-child { box-shadow: inset 3px 0 0 var(--warn); }
tfoot td { font-weight:600; border-bottom:none; }
td.income { color:var(--good); }
.nowrap { white-space:nowrap; }
.tag { display:inline-block; background:var(--tag); color:var(--muted); border-radius:5px; padding:0 .4rem; margin-left:.35rem; font-size:.78rem; }
.note { font-size:.88rem; }
select, input, textarea, button { font:inherit; color:inherit; }
select, input { background:var(--panel); border:1px solid var(--line); border-radius:6px; padding:.3rem .45rem; max-width:100%; }
select.category { width:100%; min-width:10rem; }
button { border:1px solid var(--line); background:var(--panel); border-radius:6px; padding:.35rem .8rem; cursor:pointer; }
button.primary { background:var(--accent); border-color:var(--accent); color:#fff; }
button.quiet { color:var(--muted); }
.filters { display:flex; flex-wrap:wrap; gap:.6rem; align-items:flex-end; margin-bottom:1rem; }
.filters label { display:flex; flex-direction:column; font-size:.8rem; color:var(--muted); gap:.15rem; }
.inbox { display:flex; flex-direction:column; gap:.6rem; }
.item { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:.8rem 1rem; display:flex; gap:1rem;
        justify-content:space-between; align-items:center; flex-wrap:wrap; }
.item.done { color:var(--muted); justify-content:flex-start; }
.item-main { display:flex; gap:1rem; align-items:center; }
.item .amount { font-size:1.2rem; font-weight:600; font-variant-numeric:tabular-nums; min-width:6rem; }
.item .merchant { font-weight:500; }
.item-actions { display:flex; gap:.5rem; align-items:center; flex-wrap:wrap; }
.item-actions select { width:auto; }
.suggestion { margin-right:.25rem; }
.badge { background:var(--tag); color:var(--muted); font-size:.75rem; border-radius:5px; padding:0 .35rem; margin-left:.35rem; }
.toml textarea { width:100%; min-height:28rem; font:13px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; background:var(--panel);
                 border:1px solid var(--line); border-radius:10px; padding:.8rem; tab-size:4; }
.toml .actions { display:flex; gap:.5rem; align-items:flex-start; margin:.5rem 0 1.5rem; flex-wrap:wrap; }
.result { padding:.35rem .6rem; border-radius:6px; }
.result.ok { color:var(--good); } .result.error { color:var(--over); }
.upload { display:flex; gap:.6rem; align-items:center; flex-wrap:wrap; margin-bottom:1rem; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size:.88rem; }
table.compact td { padding:.35rem .4rem; }
@media (max-width: 640px) {
  .hide-sm { display:none; }
  .bar-row { grid-template-columns:6.5rem 1fr 6.5rem; font-size:.88rem; }
  header { gap:.75rem; } .who { display:none; }
}
"""
