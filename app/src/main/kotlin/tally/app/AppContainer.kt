package tally.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tally.app.capture.CaptureNotifier
import tally.app.data.CaptureMemory
import tally.app.data.CapturePipeline
import tally.app.data.CategoryRepository
import tally.app.data.DefaultData
import tally.app.data.LedgerRepository
import tally.app.data.RawEventRepository
import tally.app.data.RulesRepository
import tally.app.data.SettingsRepository
import tally.app.data.TallyDatabase
import tally.app.sync.SyncManager
import tally.app.sync.SyncSettings
import tally.app.widget.QuickAddWidget
import tally.core.model.TransactionStatus

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
    val sync = SyncManager(appContext, database, SyncSettings(context, appScope)) { tables ->
        // Aliases edited on the dashboard: re-file old transactions, as an edit here would.
        if ("aliases" in tables) rules.reapplyAliases()
        if ("transactions" in tables) {
            CaptureNotifier.dismissHandled(appContext) { id ->
                database.transactionDao().getById(id)?.status == TransactionStatus.CAPTURED.name
            }
        }
    }

    init {
        appScope.launch { DefaultData.seedIfEmpty(database) }
        appScope.launch { QuickAddWidget.refreshOnChanges(appContext, ledger) }
        // WorkManager is only touched once sync is set up (it isn't in Robolectric tests).
        appScope.launch { if (sync.settings.currentServer().isComplete) sync.updateSchedule() }
        sync.watchLocalChanges(appScope)
    }
}
