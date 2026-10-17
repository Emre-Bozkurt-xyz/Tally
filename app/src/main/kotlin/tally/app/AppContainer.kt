package tally.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tally.app.data.CaptureMemory
import tally.app.data.CapturePipeline
import tally.app.data.CategoryRepository
import tally.app.data.DefaultData
import tally.app.data.LedgerRepository
import tally.app.data.RawEventRepository
import tally.app.data.RulesRepository
import tally.app.data.SettingsRepository
import tally.app.data.TallyDatabase
import tally.app.widget.QuickAddWidget

/** Hand-rolled dependency container, created once in [TallyApplication.onCreate]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = TallyDatabase.build(context)
    val rawEvents = RawEventRepository(database.rawEventDao())
    val settings = SettingsRepository(context, appScope)
    val captureMemory = CaptureMemory(database)
    val ledger = LedgerRepository(database, memory = captureMemory)
    val capture = CapturePipeline(database, captureMemory)
    val categories = CategoryRepository(database)
    val rules = RulesRepository(database)

    init {
        appScope.launch { DefaultData.seedIfEmpty(database) }
        appScope.launch { QuickAddWidget.refreshOnChanges(appContext, ledger) }
    }
}
