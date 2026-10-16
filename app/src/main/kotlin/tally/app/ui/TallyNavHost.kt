package tally.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tally.app.AppContainer
import tally.app.ui.categories.CategoriesScreen
import tally.app.ui.ledger.LedgerScreen
import tally.app.ui.ledger.MonthScreen
import tally.app.ui.rawevents.RawEventDetailScreen
import tally.app.ui.rawevents.RawEventsScreen
import tally.app.ui.setup.SetupScreen

private object Routes {
    const val LEDGER = "ledger"
    const val MONTH = "month"
    const val CATEGORIES = "categories"
    const val RAW_EVENTS = "raw-events"
    const val RAW_EVENT = "raw-events/{id}"
    const val SETUP = "setup"

    fun rawEvent(id: String) = "raw-events/$id"
}

@Composable
fun TallyNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LEDGER) {
        composable(Routes.LEDGER) {
            LedgerScreen(
                ledger = container.ledger,
                onOpenMonth = { nav.navigate(Routes.MONTH) },
                onOpenCategories = { nav.navigate(Routes.CATEGORIES) },
                onOpenSetup = { nav.navigate(Routes.SETUP) },
                onOpenRawEvents = { nav.navigate(Routes.RAW_EVENTS) },
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
        composable(Routes.RAW_EVENTS) {
            RawEventsScreen(
                repository = container.rawEvents,
                onOpenEvent = { nav.navigate(Routes.rawEvent(it)) },
                onOpenSetup = { nav.navigate(Routes.SETUP) },
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
