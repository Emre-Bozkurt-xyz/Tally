package tally.server.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tally.server.caller
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The dashboard: Overview, Ledger, Inbox and Rules. Mounted behind Cloudflare Access. */
fun Route.dashboard(edits: WebEdits, zone: ZoneId, clock: Clock) {
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
        call.respondHtml { page("Rules", Tab.RULES, s, call.caller().name) { rules(s, edits.exportToml(s)) } }
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

    edit("/inbox/{id}/ignore") { id ->
        val done = withContext(Dispatchers.IO) { edits.ignore(id, source()) }
        html(inboxDoneHtml(if (done) "Deleted." else "Already handled."))
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

private suspend fun ApplicationCall.html(fragment: String) = respondText(fragment, ContentType.Text.Html)
