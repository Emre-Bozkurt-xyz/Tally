package tally.server.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tally.core.rules.SuggestedAlias
import tally.core.rules.SuggestedRule
import tally.server.caller
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The dashboard: Overview, Ledger, Inbox and Rules. Mounted behind Cloudflare Access. */
fun Route.dashboard(edits: WebEdits, rules: RulesEdits, suggester: RuleSuggester, statements: StatementImport, zone: ZoneId, clock: Clock) {
    fun today() = LocalDate.now(clock.withZone(zone))
    suspend fun snapshot() = withContext(Dispatchers.IO) { edits.snapshot() }

    get("/") {
        val month = call.request.queryParameters["month"]?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(today())
        val s = snapshot()
        call.respondHtml { page("Overview", Tab.OVERVIEW, s, call.caller().name, charts = true) { overview(Overview(s, month, today())) } }
    }

    get("/ledger") {
        val params = call.request.queryParameters
        val month = YearMonth.from(today())
        fun date(name: String) = params[name]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val query = LedgerQuery(
            from = date("from") ?: month.atDay(1),
            to = date("to") ?: month.atEndOfMonth(),
            categoryId = params["category"]?.takeIf { it.isNotBlank() },
            accountId = params["account"]?.takeIf { it.isNotBlank() },
            tagId = params["tag"]?.takeIf { it.isNotBlank() },
            text = params["q"]?.takeIf { it.isNotBlank() },
        )
        val s = snapshot()
        call.respondHtml { page("Ledger", Tab.LEDGER, s, call.caller().name) { ledger(s, query) } }
    }

    get("/inbox") {
        val s = snapshot()
        call.respondHtml { page("Inbox", Tab.INBOX, s, call.caller().name) { inbox(s) } }
    }

    get("/rules") {
        val s = snapshot()
        call.respondHtml { page("Rules", Tab.RULES, s, call.caller().name) { rulesPage(s, rules, suggester.canAsk, edits.exportToml(s)) } }
    }

    get("/statements") {
        val s = snapshot()
        call.respondHtml { page("Statements", Tab.STATEMENTS, s, call.caller().name) { statements() } }
    }

    edit("/statements/preview") {
        var pdf: ByteArray? = null
        receiveMultipart(formFieldLimit = MAX_PDF_BYTES).forEachPart { part ->
            if (part is PartData.FileItem && part.name == "pdf") pdf = part.provider().readRemaining().readByteArray()
            part.dispose()
        }
        val bytes = pdf?.takeIf { it.isNotEmpty() } ?: return@edit html(statementErrorHtml("Pick a PDF first."))
        val token = withContext(Dispatchers.IO) { runCatching { statements.read(bytes) }.getOrNull() }
            ?: return@edit html(statementErrorHtml("That isn't an RBC statement Tally can read (chequing or credit card PDF)."))
        val s = snapshot()
        val statement = statements.statement(token)!!
        html(statementPreviewHtml(s, token, statements.plan(s, statement, statements.guessAccount(s, statement))))
    }

    edit("/statements/{id}/preview") { token ->
        val accountId = receiveParameters()["account"]?.takeIf { it.isNotBlank() }
        val statement = statements.statement(token) ?: return@edit html(statementImportedHtml(null))
        val s = snapshot()
        html(statementPreviewHtml(s, token, statements.plan(s, statement, accountId?.takeIf { id -> s.accounts.any { it.id == id } })))
    }

    edit("/statements/{id}/import") { token ->
        val accountId = receiveParameters()["account"].orEmpty()
        html(statementImportedHtml(withContext(Dispatchers.IO) { statements.commit(token, accountId, source()) }))
    }

    edit("/ledger/{id}/category") { id ->
        val categoryId = receiveParameters()["categoryId"].orEmpty()
        withContext(Dispatchers.IO) { edits.setCategory(id, categoryId, source()) }
        val s = snapshot()
        val t = s.transactions.find { it.id == id } ?: return@edit html("")
        html(ledgerRowHtml(s, t))
    }

    edit("/inbox/{id}/confirm") { id ->
        val done = withContext(Dispatchers.IO) { edits.confirm(id, source()) }
        html(inboxDoneHtml(if (done) "Confirmed." else "Already handled."))
    }

    edit("/inbox/{id}/category") { id ->
        val categoryId = receiveParameters()["categoryId"].orEmpty()
        val done = withContext(Dispatchers.IO) { edits.setCategory(id, categoryId, source()) }
        val s = snapshot()
        html(inboxDoneHtml(if (done) "Filed under ${s.label(categoryId)}." else "Already handled."))
    }

    edit("/inbox/{id}/settle/{split}") { id ->
        val splitId = parameters["split"].orEmpty()
        val done = withContext(Dispatchers.IO) { edits.settle(id, splitId, source()) }
        html(inboxDoneHtml(if (done) "Settled." else "Already handled."))
    }

    edit("/inbox/{id}/ignore") { id ->
        val done = withContext(Dispatchers.IO) { edits.ignore(id, source()) }
        html(inboxDoneHtml(if (done) "Deleted." else "Already handled."))
    }

    edit("/rules/try") {
        val form = receiveParameters()
        val s = snapshot()
        html(trialHtml(s, rules.trial(s, form["raw"].orEmpty(), form["amount"].orEmpty())))
    }

    edit("/rules/suggest/history") {
        val s = snapshot()
        html(suggestionsHtml(suggester.fromHistory(s), fromModel = false))
    }

    edit("/rules/suggest/model") {
        val shown = withContext(Dispatchers.IO) {
            runCatching { suggester.fromModel(snapshot()) }
        }
        html(shown.fold({ suggestionsHtml(it, fromModel = true) }, { statementErrorHtml("Couldn't get suggestions: ${it.message}") }))
    }

    edit("/rules/suggest/add") {
        val form = receiveParameters()
        val picked = form.getAll("pick").orEmpty().toSet()
        val aliases = picked.filter { it.startsWith("a") }.mapNotNull { key ->
            val match = form["${key}_match"] ?: return@mapNotNull null
            SuggestedAlias(match, form["${key}_name"].orEmpty())
        }
        val newRules = picked.filter { it.startsWith("r") }.mapNotNull { key ->
            val merchant = form["${key}_merchant"] ?: return@mapNotNull null
            SuggestedRule(merchant, form["${key}_category"].orEmpty(), ask = form["${key}_ask"] == "true")
        }
        val problems = withContext(Dispatchers.IO) { suggester.add(aliases, newRules, source()) }
        val s = snapshot()
        html(suggestionsAddedHtml(s, rules, aliases.size, newRules.size, problems, rules.replayPlan(s)))
    }

    edit("/rules/replay/preview") {
        val s = snapshot()
        html(replayHtml(s, rules.replayPlan(s)))
    }

    edit("/rules/replay/apply") {
        html(replayDoneHtml(withContext(Dispatchers.IO) { rules.applyReplay(source()) }))
    }

    edit("/rules/rule/save") {
        val f = receiveParameters()
        val form = RulesEdits.RuleForm(
            id = f["id"]?.takeIf { it.isNotBlank() },
            pattern = f["pattern"].orEmpty(),
            min = f["min"].orEmpty(),
            max = f["max"].orEmpty(),
            accountId = f["account"]?.takeIf { it.isNotBlank() },
            categoryId = f["category"].orEmpty(),
            tags = f["tags"].orEmpty(),
            ask = f["ask"] == "true",
        )
        val error = withContext(Dispatchers.IO) { rules.saveRule(form, source()) }
        if (error != null) return@edit html(ruleRowHtml(snapshot(), form, error))
        section("#rules-editor", rulesSectionHtml(snapshot(), rules))
    }
    for ((action, by) in listOf("up" to -1, "down" to 1)) {
        edit("/rules/rule/{id}/$action") { id ->
            withContext(Dispatchers.IO) { rules.moveRule(id, by, source()) }
            section("#rules-editor", rulesSectionHtml(snapshot(), rules))
        }
        edit("/rules/alias/{id}/$action") { id ->
            withContext(Dispatchers.IO) { rules.moveAlias(id, by, source()) }
            section("#aliases-editor", aliasesSectionHtml(snapshot(), rules))
        }
        edit("/rules/category/{id}/$action") { id ->
            withContext(Dispatchers.IO) { rules.moveCategory(id, by, source()) }
            section("#categories-editor", categoriesSectionHtml(snapshot()))
        }
    }
    edit("/rules/rule/{id}/delete") { id ->
        withContext(Dispatchers.IO) { rules.deleteRule(id, source()) }
        section("#rules-editor", rulesSectionHtml(snapshot(), rules))
    }

    edit("/rules/alias/save") {
        val f = receiveParameters()
        val id = f["id"]?.takeIf { it.isNotBlank() }
        val error = withContext(Dispatchers.IO) { rules.saveAlias(id, f["pattern"].orEmpty(), f["name"].orEmpty(), source()) }
        if (error != null) return@edit html(aliasRowHtml(id, f["pattern"].orEmpty(), f["name"].orEmpty(), error))
        section("#aliases-editor", aliasesSectionHtml(snapshot(), rules))
    }
    edit("/rules/alias/{id}/delete") { id ->
        withContext(Dispatchers.IO) { rules.deleteAlias(id, source()) }
        section("#aliases-editor", aliasesSectionHtml(snapshot(), rules))
    }

    edit("/rules/category/add") {
        val f = receiveParameters()
        val parent = f["parent"]?.takeIf { it.isNotBlank() }
        val error = withContext(Dispatchers.IO) { rules.addCategory(f["name"].orEmpty(), parent, source()) }
        if (error != null) return@edit html(categoryAddErrorHtml(snapshot(), f["name"].orEmpty(), parent, error))
        section("#categories-editor", categoriesSectionHtml(snapshot()))
    }
    edit("/rules/category/{id}/save") { id ->
        val f = receiveParameters()
        val parent = f["parent"]?.takeIf { it.isNotBlank() }
        val name = f["name"].orEmpty()
        val budget = f["budget"].orEmpty()
        val pinned = f["pinned"] == "true"
        val error = withContext(Dispatchers.IO) { rules.updateCategory(id, name, parent, budget, pinned, source()) }
        if (error != null) return@edit html(categoryRowHtml(snapshot(), id, name, parent, budget, pinned, error))
        section("#categories-editor", categoriesSectionHtml(snapshot()))
    }
    edit("/rules/category/{id}/delete") { id ->
        val error = withContext(Dispatchers.IO) { rules.deleteCategory(id, source()) }
        section("#categories-editor", categoriesSectionHtml(snapshot(), error))
    }

    edit("/rules/check") {
        val text = receiveParameters()["toml"].orEmpty()
        val s = snapshot()
        html(tomlResultHtml(tally.core.rules.RulesToml.import(text, s.accounts.map { it.name }.toSet()), saved = false))
    }

    edit("/rules/save") {
        val text = receiveParameters()["toml"].orEmpty()
        html(tomlResultHtml(withContext(Dispatchers.IO) { edits.importToml(text, source()) }, saved = true))
    }
}

/**
 * A POST from the dashboard's own pages. HTMX sends `HX-Request: true`, which a form on
 * another site can't (a custom header needs CORS, which this server never grants), so the
 * check stops cross-site requests riding on the Access cookie.
 */
private fun Route.edit(path: String, handle: suspend ApplicationCall.(id: String) -> Unit) {
    post(path) {
        if (call.request.headers["HX-Request"] != "true") {
            call.respondText("Edits come from the dashboard only", status = HttpStatusCode.Forbidden)
            return@post
        }
        call.handle(call.parameters["id"].orEmpty())
    }
}

/** The page that sent the edit (`X-Tally-Source`, set by HTMX), so it can skip its own echo. */
private fun ApplicationCall.source(): String =
    request.headers["X-Tally-Source"]?.takeIf { it.matches(SOURCE) } ?: WebEdits.WEB

private val SOURCE = Regex("web-[A-Za-z0-9]{1,32}")

private const val MAX_PDF_BYTES = 20L * 1024 * 1024

/** A whole editor section, swapped in place of [target] (the row that was saved asked for itself). */
private suspend fun ApplicationCall.section(target: String, fragment: String) {
    response.header("HX-Retarget", target)
    response.header("HX-Reswap", "outerHTML")
    html(fragment)
}

private suspend fun ApplicationCall.html(fragment: String) = respondText(fragment, ContentType.Text.Html)
