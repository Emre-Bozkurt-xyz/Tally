package tally.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tally.app.AppContainer
import tally.app.data.CapturePipeline
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.categories.CategoriesScreen
import tally.app.ui.components.LocalBottomClearance
import tally.app.ui.components.Tab
import tally.app.ui.components.TallyBottomBar
import tally.app.ui.components.bottomClearance
import tally.app.ui.home.HomeScreen
import tally.app.ui.inbox.InboxScreen
import tally.app.ui.insights.OwedScreen
import tally.app.ui.insights.StatsScreen
import tally.app.ui.insights.SubscriptionsScreen
import tally.app.ui.ledger.LedgerScreen
import tally.app.ui.ledger.MonthScreen
import tally.app.ui.more.MoreScreen
import tally.app.ui.rawevents.RawEventDetailScreen
import tally.app.ui.rawevents.RawEventsScreen
import tally.app.ui.rules.AliasesScreen
import tally.app.ui.rules.RulesScreen
import tally.app.ui.rules.TomlEditorScreen
import tally.app.ui.setup.SetupScreen
import tally.app.ui.sync.SyncScreen
import tally.app.ui.transaction.TransactionScreen
import tally.core.model.ParseStatus

private object Routes {
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
    const val SYNC = "sync"

    fun rawEvent(id: String) = "raw-events/$id"

    fun transaction(id: String) = "transactions/$id"
}

private fun NavHostController.openTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun TallyNavHost(container: AppContainer, openTransactionId: String? = null) {
    val context = LocalContext.current
    val nav = rememberNavController()
    // Opened from a capture notification's Split action.
    LaunchedEffect(openTransactionId) {
        openTransactionId?.let { nav.navigate(Routes.transaction(it)) }
    }
    val entry by nav.currentBackStackEntryAsState()
    val tab = Tab.entries.find { it.route == entry?.destination?.route }
    val inboxCount by remember { container.ledger.observeInboxCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val open: (String) -> Unit = { nav.navigate(it) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalBottomClearance provides bottomClearance()) {
            NavHost(
                navController = nav,
                startDestination = Tab.Home.route,
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                exitTransition = { fadeOut(tween(180)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 12 } },
            ) {
                composable(Tab.Home.route, enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) }) {
                    HomeScreen(
                        ledger = container.ledger,
                        onOpenMonth = { open(Routes.MONTH) },
                        onOpenInbox = { nav.openTab(Tab.Inbox) },
                        onOpenActivity = { nav.openTab(Tab.Activity) },
                        onOpenTransaction = { open(Routes.transaction(it)) },
                    )
                }
                composable(Tab.Activity.route, enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) }) {
                    LedgerScreen(ledger = container.ledger, onOpenTransaction = { open(Routes.transaction(it)) })
                }
                composable(Tab.Inbox.route, enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) }) {
                    InboxScreen(
                        ledger = container.ledger,
                        rules = container.rules,
                        onOpenRawEvent = { open(Routes.rawEvent(it)) },
                    )
                }
                composable(Tab.More.route, enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) }) {
                    MoreScreen(
                        onOpenMonth = { open(Routes.MONTH) },
                        onOpenStats = { open(Routes.STATS) },
                        onOpenSubscriptions = { open(Routes.SUBSCRIPTIONS) },
                        onOpenOwed = { open(Routes.OWED) },
                        onOpenCategories = { open(Routes.CATEGORIES) },
                        onOpenRules = { open(Routes.RULES) },
                        onOpenAliases = { open(Routes.ALIASES) },
                        onOpenSync = { open(Routes.SYNC) },
                        onOpenSetup = { open(Routes.SETUP) },
                        onOpenRawEvents = { open(Routes.RAW_EVENTS) },
                    )
                }
                composable(Routes.TRANSACTION) { entry ->
                    TransactionScreen(
                        id = entry.arguments?.getString("id").orEmpty(),
                        ledger = container.ledger,
                        rules = container.rules,
                        onOpenRawEvent = { open(Routes.rawEvent(it)) },
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
                        onOpenTransaction = { open(Routes.transaction(it)) },
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
                        onEditToml = { open(Routes.RULES_TOML) },
                        onOpenAliases = { open(Routes.ALIASES) },
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
                        onOpenEvent = { open(Routes.rawEvent(it)) },
                        onOpenSetup = { open(Routes.SETUP) },
                        onReparse = { reparseSummary(container.capture.reparseUnlinked()) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.RAW_EVENT) { entry ->
                    RawEventDetailScreen(
                        repository = container.rawEvents,
                        id = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.SYNC) {
                    SyncScreen(sync = container.sync, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETUP) {
                    SetupScreen(
                        container = container,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
        if (tab != null) {
            TallyBottomBar(
                current = tab,
                inboxCount = inboxCount,
                onSelect = { nav.openTab(it) },
                onAdd = { context.startActivity(QuickEntryActivity.intent(context)) },
                modifier = Modifier.align(Alignment.BottomCenter),
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
