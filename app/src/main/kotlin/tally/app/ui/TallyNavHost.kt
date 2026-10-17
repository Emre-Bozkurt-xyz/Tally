package tally.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tally.app.AppContainer
import tally.app.data.CapturePipeline
import tally.app.ui.categories.CategoriesScreen
import tally.app.ui.inbox.InboxScreen
import tally.app.ui.insights.OwedScreen
import tally.app.ui.insights.StatsScreen
import tally.app.ui.insights.SubscriptionsScreen
import tally.app.ui.ledger.LedgerScreen
import tally.app.ui.ledger.MonthScreen
import tally.app.ui.rawevents.RawEventDetailScreen
import tally.app.ui.rawevents.RawEventsScreen
import tally.app.ui.rules.AliasesScreen
import tally.app.ui.rules.RulesScreen
import tally.app.ui.rules.TomlEditorScreen
import tally.app.ui.setup.SetupScreen
import tally.app.ui.transaction.TransactionScreen
import tally.core.model.ParseStatus

private object Routes {
    const val LEDGER = "ledger"
    const val INBOX = "inbox"
    const val TRANSACTION = "transactions/{id}"
    const val MONTH = "month"
    const val STATS = "stats"
    const val SUBSCRIPTIONS = "subscriptions"
    const val OWED = "owed"
    const val CATEGORIES = "categories"
    const val RULES = "rules"
    const val RULES_TOML = "rules/toml"
    const val ALIASES = "rules/aliases"
    const val RAW_EVENTS = "raw-events"
    const val RAW_EVENT = "raw-events/{id}"
    const val SETUP = "setup"

    fun rawEvent(id: String) = "raw-events/$id"

    fun transaction(id: String) = "transactions/$id"
}

@Composable
fun TallyNavHost(container: AppContainer, openTransactionId: String? = null) {
    val nav = rememberNavController()
    // Opened from a capture notification's Split action.
    LaunchedEffect(openTransactionId) {
        openTransactionId?.let { nav.navigate(Routes.transaction(it)) }
    }
    NavHost(navController = nav, startDestination = Routes.LEDGER) {
        composable(Routes.LEDGER) {
            LedgerScreen(
                ledger = container.ledger,
                onOpenMonth = { nav.navigate(Routes.MONTH) },
                onOpenInbox = { nav.navigate(Routes.INBOX) },
                onOpenCategories = { nav.navigate(Routes.CATEGORIES) },
                onOpenRules = { nav.navigate(Routes.RULES) },
                onOpenStats = { nav.navigate(Routes.STATS) },
                onOpenSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) },
                onOpenOwed = { nav.navigate(Routes.OWED) },
                onOpenSetup = { nav.navigate(Routes.SETUP) },
                onOpenRawEvents = { nav.navigate(Routes.RAW_EVENTS) },
                onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
            )
        }
        composable(Routes.TRANSACTION) { entry ->
            TransactionScreen(
                id = entry.arguments?.getString("id").orEmpty(),
                ledger = container.ledger,
                rules = container.rules,
                onOpenRawEvent = { nav.navigate(Routes.rawEvent(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.INBOX) {
            InboxScreen(
                ledger = container.ledger,
                rules = container.rules,
                onOpenRawEvent = { nav.navigate(Routes.rawEvent(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(ledger = container.ledger, onBack = { nav.popBackStack() })
        }
        composable(Routes.SUBSCRIPTIONS) {
            SubscriptionsScreen(ledger = container.ledger, onBack = { nav.popBackStack() })
        }
        composable(Routes.OWED) {
            OwedScreen(
                ledger = container.ledger,
                onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MONTH) {
            MonthScreen(ledger = container.ledger, onBack = { nav.popBackStack() })
        }
        composable(Routes.CATEGORIES) {
            CategoriesScreen(
                ledger = container.ledger,
                repository = container.categories,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RULES) {
            RulesScreen(
                rules = container.rules,
                ledger = container.ledger,
                onEditToml = { nav.navigate(Routes.RULES_TOML) },
                onOpenAliases = { nav.navigate(Routes.ALIASES) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RULES_TOML) {
            TomlEditorScreen(rules = container.rules, onBack = { nav.popBackStack() })
        }
        composable(Routes.ALIASES) {
            AliasesScreen(rules = container.rules, onBack = { nav.popBackStack() })
        }
        composable(Routes.RAW_EVENTS) {
            RawEventsScreen(
                repository = container.rawEvents,
                onOpenEvent = { nav.navigate(Routes.rawEvent(it)) },
                onOpenSetup = { nav.navigate(Routes.SETUP) },
                onReparse = { reparseSummary(container.capture.reparseUnlinked()) },
            )
        }
        composable(Routes.RAW_EVENT) { entry ->
            RawEventDetailScreen(
                repository = container.rawEvents,
                id = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

private fun reparseSummary(outcomes: List<CapturePipeline.Outcome>): String {
    if (outcomes.isEmpty()) return "Nothing to re-parse"
    val captured = outcomes.count { it is CapturePipeline.Outcome.Captured }
    val failed = outcomes.count { it is CapturePipeline.Outcome.Marked && it.status == ParseStatus.FAILED }
    return "Re-parsed ${outcomes.size}: $captured captured, $failed not recognized"
}
