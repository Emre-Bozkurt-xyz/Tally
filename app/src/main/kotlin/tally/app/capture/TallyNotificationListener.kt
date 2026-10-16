package tally.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tally.app.appContainer
import tally.app.data.RawEventEntity
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import java.time.ZoneId
import java.util.UUID

/**
 * Records notifications from watched packages as raw events. Does nothing clever: all
 * parsing happens later, in `core`, over the stored events.
 */
class TallyNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val container = appContainer
        if (pkg !in WatchedPackages.ALL && !container.settings.logAllPackages.value) return

        // Read everything off the notification now; the extras Bundle can change later.
        val event = try {
            toRawEvent(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't read notification from $pkg", t)
            return
        }
        scope.launch {
            try {
                container.rawEvents.record(event)
            } catch (t: Throwable) {
                Log.e(TAG, "Couldn't store notification from $pkg", t)
            }
        }
    }

    override fun onListenerDisconnected() {
        // One UI and others occasionally unbind listeners; ask to be bound again.
        requestRebind(ComponentName(this, TallyNotificationListener::class.java))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toRawEvent(sbn: StatusBarNotification): RawEventEntity {
        val notification = sbn.notification
        val extras = notification.extras
        return RawEventEntity(
            id = UUID.randomUUID().toString(),
            source = sbn.packageName,
            receivedAt = System.currentTimeMillis(),
            postedAt = sbn.postTime,
            zoneId = ZoneId.systemDefault().id,
            sbnKey = sbn.key,
            channelId = notification.channelId,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extrasJson = ExtrasJson.toJson(extras).toString(),
            parseStatus = ParseStatus.PENDING.name,
            transactionId = null,
        )
    }

    private companion object {
        const val TAG = "TallyListener"
    }
}
