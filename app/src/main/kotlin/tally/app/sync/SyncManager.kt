package tally.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import tally.app.appContainer
import tally.app.data.TallyDatabase
import tally.core.sync.SyncProtocol
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Runs syncs and decides when: every hour, when the app opens, and shortly after local
 * changes. Does nothing until the server settings are filled in.
 */
class SyncManager(
    private val context: Context,
    private val db: TallyDatabase,
    val settings: SyncSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    val transport = HttpSyncTransport(settings::currentServer)
    private val client = SyncClient(db, transport, settings, settings::deviceId, clock)

    sealed interface Outcome {
        data class Synced(val result: SyncResult) : Outcome
        data class Failed(val message: String, val retry: Boolean) : Outcome
    }

    suspend fun syncNow(): Outcome {
        if (!settings.currentServer().isComplete) return Outcome.Failed("Sync isn't set up", retry = false)
        val outcome = try {
            Outcome.Synced(client.sync())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncAuthException) {
            Outcome.Failed(e.message ?: "Not signed in", retry = false)
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName, retry = true)
        } catch (e: SerializationException) {
            // A row this version can't read: an app update is needed, retrying won't help.
            Outcome.Failed("Unreadable data from the server: ${e.message}", retry = false)
        }
        val now = clock.millis()
        when (outcome) {
            is Outcome.Synced -> settings.recordSuccess(now, outcome.result.summary())
            is Outcome.Failed -> settings.recordFailure(now, outcome.message)
        }
        return outcome
    }

    /** Queues a sync for when there's a network, after [delaySeconds]. */
    suspend fun requestSync(delaySeconds: Long = 0) {
        if (!settings.currentServer().isComplete) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // Runs after a sync already in progress instead of cancelling it.
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Hourly sync on or off, to match whether the settings are complete. */
    suspend fun updateSchedule() {
        val work = WorkManager.getInstance(context)
        if (settings.currentServer().isComplete) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(connected).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(PERIODIC)
        }
    }

    /**
     * Syncs [CHANGE_DELAY_SECONDS] after the last local change in a burst. Raw events are
     * left out: with "log all packages" on they change constantly, and the capture that
     * matters writes a transaction anyway.
     */
    @OptIn(FlowPreview::class)
    fun watchLocalChanges(scope: CoroutineScope) = scope.launch {
        val tables = (SyncProtocol.TABLES - "raw_events").toTypedArray()
        db.invalidationTracker.createFlow(*tables, emitInitialState = false)
            .debounce(CHANGE_DELAY_SECONDS * 1000)
            .collect { requestSync() }
    }

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        private const val SOON = "sync-soon"
        private const val PERIODIC = "sync-periodic"
        const val CHANGE_DELAY_SECONDS = 30L
    }
}

fun SyncResult.summary(): String = buildString {
    append("Sent $pushed, received $pulled")
    if (rejected.isNotEmpty()) append(", ${rejected.size} refused (phone clock ahead?)")
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val outcome = applicationContext.appContainer.sync.syncNow()) {
            is SyncManager.Outcome.Synced -> Result.success()
            is SyncManager.Outcome.Failed -> if (outcome.retry && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
