package tally.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tally.app.data.DefaultData
import tally.app.data.RawEventRepository
import tally.app.data.SettingsRepository
import tally.app.data.TallyDatabase

/** Hand-rolled dependency container, created once in [TallyApplication.onCreate]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = TallyDatabase.build(context)
    val rawEvents = RawEventRepository(database.rawEventDao())
    val settings = SettingsRepository(context, appScope)

    init {
        appScope.launch { DefaultData.seedIfEmpty(database) }
    }
}
