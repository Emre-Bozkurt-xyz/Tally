package tally.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tally.app.AppContainer
import tally.app.ui.rawevents.RawEventDetailScreen
import tally.app.ui.rawevents.RawEventsScreen
import tally.app.ui.setup.SetupScreen

private object Routes {
    const val RAW_EVENTS = "raw-events"
    const val RAW_EVENT = "raw-events/{id}"
    const val SETUP = "setup"

    fun rawEvent(id: String) = "raw-events/$id"
}

@Composable
fun TallyNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.RAW_EVENTS) {
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
