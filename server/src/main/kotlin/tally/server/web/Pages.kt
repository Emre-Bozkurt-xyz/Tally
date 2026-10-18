package tally.server.web

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.canvas
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.link
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
import kotlin.math.absoluteValue

// Server-rendered pages. HTMX does the inline edits (each POST returns the fragment to swap
// in); Chart.js draws the charts. Both load pinned versions from jsDelivr. The look lives in
// Style.kt.

private const val HTMX = "https://cdn.jsdelivr.net/npm/htmx.org@2.0.4/dist/htmx.min.js"
private const val CHART_JS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"

private val dayFormat = DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)
private val dayHeader = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.CANADA)
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.CANADA)
private val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.CANADA)
private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.CANADA)

enum class Tab(val path: String, val label: String, val icon: String) {
    OVERVIEW("/", "Overview", "home"),
    LEDGER("/ledger", "Activity", "activity"),
    INBOX("/inbox", "Inbox", "inbox"),
    RULES("/rules", "Rules", "wand"),
    STATEMENTS("/statements", "Statements", "file"),
}

fun HTML.page(title: String, tab: Tab, s: Snapshot, who: String, charts: Boolean = false, content: FlowContent.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("$title · Tally")
        link(rel = "preload", href = "/assets/geist.ttf") { attributes["as"] = "font"; attributes["crossorigin"] = "" }
        style { unsafe { +(CSS + RULES_CSS) } }
        script(src = HTMX) {}
        if (charts) script(src = CHART_JS) {}
    }
    body {
        // This page's id: its HTMX edits send it back, so it can tell its own changes from others'.
        val source = "web-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        attributes["data-source"] = source
        attributes["data-live"] = if (tab == Tab.RULES || tab == Tab.STATEMENTS) "banner" else "auto"
        attributes["hx-headers"] = """{"X-Tally-Source":"$source"}"""
        div("shell") {
            aside("sidebar") {
                a(href = "/", classes = "brand") {
                    unsafe { +MARK_SVG }
                    +"Tally"
                }
                nav("nav") {
                    Tab.entries.forEach { t ->
                        a(href = t.path, classes = if (t == tab) "active" else null) {
                            attributes["aria-label"] = t.label
                            icon(t.icon)
                            span("label") { +t.label }
                            if (t == Tab.INBOX && s.needsReview.isNotEmpty()) span("count") { +"${s.needsReview.size}" }
                        }
                    }
                }
                div("who") { monoLabel(who) }
            }
            main { content() }
        }
        div("live-banner") {
            attributes["hidden"] = "hidden"
            +"Changed on another device. "
            a(href = "") { +"Refresh" }
        }
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

/** The key that colors an avatar: the top-level category, so a group shares a tint (as on the phone). */
private fun Snapshot.avatarKey(categoryId: String?): String? = categoryId?.let { parentOf(it) ?: it }

// ---------- Overview ----------

fun FlowContent.overview(o: Overview) {
    val s = o.snapshot
    val current = o.month == YearMonth.from(o.today)
    pageHead(if (current) "This month" else "Looking back", o.month.format(monthFormat)) {
        a(href = "/?month=${o.month.minusMonths(1)}", classes = "round") { attributes["aria-label"] = "Previous month"; icon("back") }
        a(href = "/?month=${o.month.plusMonths(1)}", classes = "round${if (current) " disabled" else ""}") {
            attributes["aria-label"] = "Next month"
            icon("forward")
        }
    }

    div("overview") {
        // The hero: spent this month, against the budget with a tick for an even pace.
        div("hero span-7") {
            div("row-between") {
                monoLabel("Spent in ${o.month.format(monthName)}")
                if (current) monoLabel("Day ${o.today.dayOfMonth} of ${o.month.lengthOfMonth()}")
            }
            div("big") { amount(o.summary.spentCents) }
            o.budgetCents?.let { budget ->
                val left = budget - o.summary.spentCents
                div("track") {
                    div("fill ${o.status(o.summary.spentCents, budget)?.name?.lowercase().orEmpty()}") {
                        style = "width:${pct(o.summary.spentCents, budget)}%"
                    }
                    if (current) div("tick") { style = "left:${pct(o.today.dayOfMonth.toLong(), o.month.lengthOfMonth().toLong())}%" }
                }
            }
            div("row-between note") {
                span {
                    val budget = o.budgetCents
                    when {
                        budget == null -> span("muted") { +"No budget set" }
                        budget >= o.summary.spentCents -> {
                            +"${Money.format(budget - o.summary.spentCents)} left"
                            span("muted") { +" of ${Money.format(budget)}" }
                        }
                        else -> {
                            +"${Money.format(o.summary.spentCents - budget)} over"
                            span("muted") { +" of ${Money.format(budget)}" }
                        }
                    }
                }
                paceNote(o)
            }
        }
        div("stack span-5") {
            div("card stat") {
                monoLabel("Came in")
                div("value") { amount(o.summary.incomeCents) }
                div("sub") { +"Income this month" }
            }
            if (s.needsReview.isNotEmpty()) {
                a(href = "/inbox", classes = "card stat attention") {
                    monoLabel("To review")
                    div("value") { +"${s.needsReview.size}" }
                    div("sub") { +"Captured purchases waiting for a yes →" }
                }
            } else {
                div("card stat") {
                    monoLabel("Last month")
                    div("value") { amount(o.lastMonth.spentCents) }
                    div("sub") { +"Spent in ${o.month.minusMonths(1).format(monthName)}" }
                }
            }
        }

        div("card span-8") {
            div("card-head") {
                monoLabel("Spending")
                div("segmented") {
                    attributes["role"] = "tablist"
                    listOf("month" to "Month", "six" to "6 months", "year" to "12 months").forEachIndexed { i, (key, text) ->
                        button(classes = if (i == 0) "on" else null) {
                            attributes["type"] = "button"
                            attributes["data-range"] = key
                            +text
                        }
                    }
                }
            }
            div("chart-box") { canvas { id = "spending" } }
            div("legend mono-label") {
                id = "legend"
                span { span("swatch") { }; +o.month.format(monthName) }
                span { span("swatch dashed") { }; +o.month.minusMonths(1).format(monthName) }
            }
        }
        div("card span-4") {
            div("card-head") {
                monoLabel("Where it went")
                a(href = "/ledger?from=${o.month.atDay(1)}&to=${o.month.atEndOfMonth()}", classes = "link") { +"All" }
            }
            if (o.summary.categories.isEmpty()) p("muted") { +"Nothing spent this month yet." }
            o.summary.categories.forEach { total ->
                val budget = o.budgetOf(total.categoryId)
                val status = o.status(total.cents, budget)
                val name = s.label(total.categoryId)
                a(href = ledgerLink(o.month, total.categoryId ?: LedgerQuery.UNCATEGORIZED), classes = "cat-row") {
                    avatar(name, total.categoryId, "sm")
                    span("title") { +name }
                    span {
                        amount(total.cents)
                        budget?.let { span("muted") { +" / ${Money.format(it)}" } }
                    }
                    div("track thin") {
                        div("fill ${status?.name?.lowercase().orEmpty()}") {
                            style = "width:${pct(total.cents, budget ?: o.summary.spentCents.coerceAtLeast(1))}%"
                        }
                    }
                }
                total.children.forEach { child ->
                    a(href = ledgerLink(o.month, child.categoryId ?: LedgerQuery.UNCATEGORIZED), classes = "cat-row child") {
                        span { +(child.categoryId?.let { s.categoriesById[it]?.name } ?: "Other") }
                        amount(child.cents)
                    }
                }
            }
        }

        div("card span-8") {
            div("card-head") {
                monoLabel("Recent")
                a(href = "/ledger?from=${o.month.atDay(1)}&to=${o.month.atEndOfMonth()}", classes = "link") { +"See all" }
            }
            if (o.recent.isEmpty()) p("muted") { +"Nothing logged this month." }
            div("rows") {
                o.recent.forEach { t ->
                    val name = s.merchantName(t)
                    div("r") {
                        avatar(name, s.avatarKey(t.categoryId))
                        div("grow") {
                            div("title") { +name }
                            div("sub") { +"${s.label(t.categoryId)} · ${s.date(t).format(dayFormat)}" }
                        }
                        div("end") {
                            amount(t.amountCents)
                            if (t.status == TransactionStatus.CAPTURED.name) pill("Review", "warn")
                        }
                    }
                }
            }
        }
        div("card span-4") {
            div("card-head") { monoLabel("Top merchants") }
            if (o.topMerchants.isEmpty()) p("muted") { +"No purchases with a merchant yet." }
            div("rows") {
                o.topMerchants.forEachIndexed { i, m ->
                    val name = s.merchantsById[m.merchantId]?.name ?: "?"
                    div("r") {
                        span("mono-label") { +"%02d".format(i + 1) }
                        div("grow") {
                            div("title") { +name }
                            div("sub") { +(if (m.count == 1) "1 purchase" else "${m.count} purchases") }
                        }
                        amount(m.cents)
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

/** "↓ 12% vs Sep": spending so far against the same day last month (or the whole of it, looking back). */
private fun FlowContent.paceNote(o: Overview) {
    val day = if (o.month == YearMonth.from(o.today)) o.today.dayOfMonth else o.month.lengthOfMonth()
    val before = o.cumulativeLast.getOrNull(minOf(day, o.cumulativeLast.size) - 1) ?: return
    if (before <= 0) return
    val change = (o.summary.spentCents - before) * 100 / before
    pill("${if (change > 0) "↑" else "↓"} ${change.absoluteValue}% vs ${o.month.minusMonths(1).format(shortMonth)}", if (change > 0) "warn" else "good")
}

private fun pct(part: Long, whole: Long) = "%.1f".format(Locale.ROOT, (100.0 * part.coerceAtLeast(0) / whole.coerceAtLeast(1)).coerceAtMost(100.0))

private fun ledgerLink(month: YearMonth, categoryId: String) =
    "/ledger?from=${month.atDay(1)}&to=${month.atEndOfMonth()}&category=$categoryId"

// ---------- Ledger ----------

fun FlowContent.ledger(s: Snapshot, q: LedgerQuery) {
    val rows = q.apply(s)
    pageHead("${q.from.format(dayFormat)} – ${q.to.format(dayFormat)}", "Activity")
    form(action = "/ledger", classes = "filters card") {
        label { span { +"From" }; input(InputType.date, name = "from") { value = q.from.toString() } }
        label { span { +"To" }; input(InputType.date, name = "to") { value = q.to.toString() } }
        label {
            span { +"Category" }
            select {
                name = "category"
                option { value = ""; +"Any" }
                option { value = LedgerQuery.UNCATEGORIZED; selected = q.categoryId == LedgerQuery.UNCATEGORIZED; +"Uncategorized" }
                s.categoryTree.forEach { c ->
                    option { value = c.id; selected = q.categoryId == c.id; +(if (c.parentId == null) c.name else "   ${c.name}") }
                }
            }
        }
        label {
            span { +"Account" }
            select {
                name = "account"
                option { value = ""; +"Any" }
                s.accounts.forEach { a -> option { value = a.id; selected = q.accountId == a.id; +a.name } }
            }
        }
        if (s.tags.isNotEmpty()) {
            label {
                span { +"Tag" }
                select {
                    name = "tag"
                    option { value = ""; +"Any" }
                    s.tags.forEach { t -> option { value = t.id; selected = q.tagId == t.id; +t.name } }
                }
            }
        }
        label("search") { span { +"Search" }; input(InputType.search, name = "q") { value = q.text.orEmpty(); placeholder = "Merchant or note" } }
        button(classes = "primary") { icon("search"); +"Filter" }
    }
    val shown = rows.take(LEDGER_LIMIT)
    div("card") {
        table("ledger") {
            thead { tr { th { +"Merchant" }; th { +"Category" }; th(classes = "hide-sm") { +"Account" }; th(classes = "num") { +"Amount" } } }
            tbody {
                shown.groupBy { s.date(it) }.forEach { (date, day) ->
                    tr("day") {
                        td { colSpan = "3"; monoLabel(dayLabel(date)) }
                        val spent = day.filter { it.kind == Kind.EXPENSE.name }.sumOf { it.amountCents }
                        td("num") { if (spent != 0L) monoLabel(Money.format(spent)) }
                    }
                    day.forEach { ledgerRow(s, it) }
                }
            }
            tfoot {
                tr {
                    td { colSpan = "2"; +("${rows.size} transactions" + if (rows.size > shown.size) " (first $LEDGER_LIMIT shown)" else "") }
                    td("hide-sm") {}
                    td("num") { amount(rows.sumOf { it.amountCents }) }
                }
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String = date.format(dayHeader)

private const val LEDGER_LIMIT = 500

fun TBODY.ledgerRow(s: Snapshot, t: TransactionRow) {
    tr(if (t.status == TransactionStatus.CAPTURED.name) "captured" else null) {
        td("who-cell") {
            val name = s.merchantName(t)
            avatar(name, s.avatarKey(t.categoryId), "sm")
            div {
                span("title") { +name }
                s.tagsByTransaction[t.id].orEmpty().forEach { span("tag") { +"#${it.name}" } }
                if (s.splits[t.id] != null) span("tag") { +"split" }
                t.note?.takeIf { it != name }?.let { div("note") { +it } }
            }
        }
        td { categorySelect(s, t, "/ledger/${t.id}/category", target = "closest tr") }
        td("hide-sm muted") { +(t.accountId?.let { s.accountsById[it]?.name } ?: "") }
        td("num") { amount(t.amountCents) }
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
    pageHead(if (s.needsReview.isEmpty()) "Nothing waiting" else "${s.needsReview.size} waiting", "Inbox")
    if (s.needsReview.isEmpty()) {
        div("card") { p("muted") { +"Nothing to review. Captures that need a look show up here and on your phone." } }
        return
    }
    p("muted") { +"Captured purchases waiting for you. Changes reach your phone right away while Tally is open, otherwise on its next sync." }
    div("inbox") { s.needsReview.forEach { inboxItem(s, it) } }
}

fun FlowContent.inboxItem(s: Snapshot, t: TransactionRow) {
    div("card item") {
        id = "item-${t.id}"
        val name = s.merchantName(t)
        div("item-top") {
            avatar(name, s.avatarKey(t.categoryId))
            div("grow") {
                attributes["style"] = "flex:1;min-width:0"
                div("merchant") { +name }
                div("details") {
                    +s.date(t).format(dayFormat)
                    t.accountId?.let { s.accountsById[it] }?.let { +" · ${it.name}" }
                    t.merchantRaw?.takeIf { it != name }?.let { span("mono") { +" · $it" } }
                }
            }
            amount(t.amountCents, "lg")
        }
        val settling = s.settles(t)
        div("suggestion") {
            when {
                settling != null -> {
                    monoLabel("Settles")
                    span("what") { +"Settles ${settling.owedBy}'s ${Money.format(settling.amountCents)}" }
                    pill("owed to you", "good")
                }
                t.categoryId != null -> {
                    monoLabel("Suggested")
                    span("what") {
                        +s.label(t.categoryId)
                        if (t.amountCents < 0 && !s.isIncomeCategory(t.categoryId)) span("muted") { +" (money back)" }
                    }
                    when (t.categorySource) {
                        CategorySource.RULE.name -> pill("your default")
                        CategorySource.LLM.name -> pill("from Claude")
                        CategorySource.MEMORY.name -> pill("from memory")
                    }
                }
                else -> {
                    monoLabel("Category")
                    span("what muted") { +(if (t.amountCents < 0) "Money in: income, or what it pays back?" else "None yet") }
                }
            }
        }
        div("item-actions") {
            if (settling != null) {
                button(classes = "primary") {
                    attributes["hx-post"] = "/inbox/${t.id}/settle/${settling.splitId}"
                    attributes["hx-target"] = "#item-${t.id}"
                    attributes["hx-swap"] = "outerHTML"
                    icon("check")
                    +"Settle"
                }
            } else if (t.categoryId != null) {
                button(classes = "primary") {
                    attributes["hx-post"] = "/inbox/${t.id}/confirm"
                    attributes["hx-target"] = "#item-${t.id}"
                    attributes["hx-swap"] = "outerHTML"
                    icon("check")
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

fun inboxDoneHtml(message: String): String = createHTML().div("card item done") {
    icon("check")
    +message
}

// ---------- Statements ----------

fun FlowContent.statements() {
    pageHead("RBC chequing and Visa", "Statements")
    p("muted") {
        +"Upload a statement PDF. Tally checks it against what it captured: "
        +"matching purchases take the statement's final amount, ones it never saw are added "
        +"(to the inbox unless a rule or merchant memory knows the category), and anything missing from the statement is listed. "
        +"You see all of it before anything is saved. Importing the same statement twice changes nothing."
    }
    form(classes = "upload") {
        attributes["hx-post"] = "/statements/preview"
        attributes["hx-encoding"] = "multipart/form-data"
        attributes["hx-target"] = "#statement"
        icon("upload")
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
            span { +"Account" }
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
                icon("check")
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
        div("card") {
            table("compact") {
                tbody {
                    plan.added.forEach { a ->
                        tr(if (a.transaction.status == TransactionStatus.CAPTURED.name) "captured" else null) {
                            td("nowrap muted") { +a.line.date.format(dayFormat) }
                            td { +(a.merchant?.name ?: a.transaction.merchantId?.let { s.merchantsById[it]?.name } ?: a.line.merchant) }
                            td("muted") {
                                +when {
                                    a.transaction.kind != Kind.EXPENSE.name -> a.transaction.kind.lowercase()
                                    a.transaction.categoryId != null -> s.label(a.transaction.categoryId)
                                    else -> "to review"
                                }
                            }
                            td("num") { amount(a.line.amountCents) }
                        }
                    }
                }
            }
        }
    }
    if (plan.matched.isNotEmpty()) {
        h2 { +"Matched" }
        div("card") {
            table("compact") {
                tbody {
                    plan.matched.forEach { m ->
                        tr {
                            td("nowrap muted") { +m.line.date.format(dayFormat) }
                            td { +m.line.merchant }
                            td("muted") { +"${s.merchantName(m.transaction)} · ${s.date(m.transaction).format(dayFormat)}" }
                            td("num") {
                                if (m.line.amountCents != m.transaction.amountCents) span("muted") { +"${signed(m.transaction.amountCents)} → " }
                                amount(m.line.amountCents)
                            }
                        }
                    }
                }
            }
        }
    }
    if (plan.missing.isNotEmpty()) {
        h2 { +"In Tally, not on the statement" }
        p("muted") { +"Maybe a different account, a duplicate, or a purchase that was cancelled. Nothing changes for these." }
        div("card") {
            table("compact") {
                tbody {
                    plan.missing.forEach { t ->
                        tr {
                            td("nowrap muted") { +s.date(t).format(dayFormat) }
                            td { +s.merchantName(t) }
                            td("num") { amount(t.amountCents) }
                        }
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
    div("card stat") {
        monoLabel(label)
        div("value") { +"$count" }
        sub?.let { div("sub") { +it } }
    }
}

private fun signed(cents: Long) = if (cents < 0) "+${Money.format(-cents)}" else Money.format(cents)

// ---------- Rules ----------

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

// The spending chart: this month's running total against last month's, or monthly bars, styled
// like the app's (ink line over a soft green fill, dashed last month, dark pill tooltips).
private const val CHARTS_JS = """
(() => {
  const css = getComputedStyle(document.documentElement), v = n => css.getPropertyValue(n).trim();
  const ink = v('--ink'), faint = v('--faint'), accent = v('--accent'), line = v('--line'), chip = v('--chip-strong'),
        tipBg = v('--primary'), tipFg = v('--on-primary');
  Chart.defaults.color = faint;
  Chart.defaults.font.family = 'Geist, system-ui, sans-serif';
  const mono = {family: '"Geist Mono", monospace', size: 10};
  const money = v => '$' + Number(v).toLocaleString(undefined, {maximumFractionDigits: 0});
  const cents = v => '$' + Number(v).toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});
  const tooltip = {backgroundColor: tipBg, titleColor: tipFg, bodyColor: tipFg, footerColor: tipFg, cornerRadius: 14, padding: 10,
                   displayColors: false, titleFont: mono, bodyFont: {family: 'Geist', size: 13, weight: '500'}, caretSize: 0};
  const scales = {
    x: {grid: {display: false}, border: {display: false}, ticks: {font: mono, maxTicksLimit: 7, maxRotation: 0}},
    y: {grid: {color: line}, border: {display: false}, ticks: {font: mono, callback: money, maxTicksLimit: 5}, beginAtZero: true},
  };
  const fill = ctx => {
    const {chart} = ctx, area = chart.chartArea;
    if (!area) return 'transparent';
    const g = chart.ctx.createLinearGradient(0, area.top, 0, area.bottom);
    g.addColorStop(0, accent + '40'); g.addColorStop(1, accent + '00');
    return g;
  };
  const canvas = document.getElementById('spending');
  let chart;
  const draw = range => {
    if (chart) chart.destroy();
    document.getElementById('legend').hidden = range !== 'month';
    if (range === 'month') {
      const datasets = [
        {label: tally.thisLabel, data: tally.thisMonth, borderColor: ink, backgroundColor: fill, fill: 'origin', borderWidth: 2.4,
         tension: .35, pointRadius: 0, pointHoverRadius: 5, pointHoverBackgroundColor: accent, pointHoverBorderColor: v('--card'), pointHoverBorderWidth: 3, spanGaps: false},
        {label: tally.lastLabel, data: tally.lastMonth, borderColor: faint, borderDash: [5, 5], borderWidth: 1.6, tension: .35, pointRadius: 0, pointHoverRadius: 0},
      ];
      if (tally.budget !== null) datasets.push({label: 'Budget', data: tally.days.map(() => tally.budget), borderColor: faint, borderWidth: 1, borderDash: [2, 4], pointRadius: 0, pointHoverRadius: 0});
      chart = new Chart(canvas, {type: 'line', data: {labels: tally.days, datasets}, options: {
        maintainAspectRatio: false, interaction: {mode: 'index', intersect: false}, scales,
        plugins: {legend: {display: false}, tooltip: {...tooltip,
          callbacks: {title: items => tally.thisLabel + ' ' + items[0].label, label: c => c.dataset.label + '  ' + cents(c.parsed.y)}}}}});
    } else {
      const n = range === 'six' ? 6 : 12;
      const labels = tally.months.slice(-n), spent = tally.spent.slice(-n), income = tally.income.slice(-n);
      chart = new Chart(canvas, {type: 'bar', data: {labels, datasets: [{label: 'Spent', data: spent,
        backgroundColor: labels.map((_, i) => i === labels.length - 1 ? accent : chip), hoverBackgroundColor: ink,
        borderRadius: 10, borderSkipped: false, maxBarThickness: 44}]},
        options: {maintainAspectRatio: false, scales, plugins: {legend: {display: false}, tooltip: {...tooltip,
          callbacks: {label: c => 'Spent  ' + cents(c.parsed.y), footer: items => 'In  ' + cents(income[items[0].dataIndex])}}}}});
    }
  };
  document.querySelectorAll('[data-range]').forEach(b => b.addEventListener('click', () => {
    document.querySelectorAll('[data-range]').forEach(x => x.classList.toggle('on', x === b));
    draw(b.dataset.range);
  }));
  document.fonts.ready.then(() => draw('month'));
})();
"""
