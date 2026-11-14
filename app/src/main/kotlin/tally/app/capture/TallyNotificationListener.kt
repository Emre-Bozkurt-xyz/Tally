package tally.app.capture

import android.app.Notification
import android.content.ComponentName
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tally.app.appContainer
import tally.app.data.CapturePipeline
import tally.app.data.RawEventEntity
import tally.app.data.recentSplitPerson
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import java.time.ZoneId
import java.util.UUID

/**
 * Records notifications from watched packages as raw events, then hands each to the
 * [CapturePipeline]. Does nothing clever itself: parsing and matching live in `core`.
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
            if (pkg in WatchedPackages.MESSAGING) interacText(sbn) ?: return else toRawEvent(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't read notification from $pkg", t)
            return
        }
        scope.launch {
            try {
                if (!container.rawEvents.record(event)) return@launch
                val outcome = container.capture.process(event)
                if (outcome is CapturePipeline.Outcome.Captured) {
                    CaptureNotifier.onCaptured(this@TallyNotificationListener, outcome, container.database.recentSplitPerson())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Couldn't store or process notification from $pkg", t)
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

    /**
     * From a messaging app, only an Interac e-Transfer text, even with "log all packages" on:
     * anything else is never stored. Keeps just that message's text and its own timestamp (so
     * a re-post of the conversation is the same event), and none of the conversation's extras.
     */
    private fun interacText(sbn: StatusBarNotification): RawEventEntity? {
        val extras = sbn.notification.extras
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java),
        )
        val last = messages.lastOrNull()
        val text = last?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!WatchedPackages.wanted(sbn.packageName, text)) return null
        return toRawEvent(sbn).copy(
            postedAt = last?.timestamp ?: sbn.postTime,
            text = text,
            bigText = null,
            extrasJson = "{}",
        )
    }

    private fun toRawEvent(sbn: StatusBarNotification): RawEventEntity {
        val notification = sbn.notification
        val extras = notification.extras
        val now = System.currentTimeMillis()
        return RawEventEntity(
            id = UUID.randomUUID().toString(),
            source = sbn.packageName,
            receivedAt = now,
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
            updatedAt = now,
        )
    }

    private companion object {
        const val TAG = "TallyListener"
    }
}
