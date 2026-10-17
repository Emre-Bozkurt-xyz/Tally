package tally.app.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import tally.app.MainActivity
import tally.app.R
import tally.app.data.CapturePipeline
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.ledger.displayAmount
import tally.core.model.CategorySource

/**
 * The "$34.32 · Foodie Fruitie" notification for a capture that needs a look. Tapping it
 * accepts the suggested category (or opens the editor when there is none); Change opens the
 * editor; Ignore deletes the transaction.
 */
object CaptureNotifier {
    private const val CHANNEL_ID = "captures"
    private const val NOTIFICATION_ID = 1 // Tagged with the transaction id, so one per transaction.

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Captured purchases", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Card and wallet purchases Tally logged for you to confirm"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Shows, updates, or clears the notification to match the capture's state. */
    fun onCaptured(context: Context, captured: CapturePipeline.Outcome.Captured) {
        val transaction = captured.transaction
        if (!captured.needsReview) {
            cancel(context, transaction.id)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = "${displayAmount(transaction.amountCents)} · ${captured.merchantName ?: "Card purchase"}"
        val guess = if (transaction.categorySource == CategorySource.LLM.name) " (suggested)" else ""
        val text = captured.categoryName?.let { "$it$guess · tap to confirm" } ?: "Tap to pick a category"
        val change = PendingIntent.getActivity(
            context,
            0,
            QuickEntryActivity.intent(context, transactionId = transaction.id)
                .setData("tally://capture/${transaction.id}/change".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tally)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(transaction.occurredAt)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                if (captured.categoryName != null) action(context, CaptureActionReceiver.ACTION_CONFIRM, transaction.id) else change,
            )
            .addAction(0, "Change", change)
            .addAction(
                0,
                "Split",
                PendingIntent.getActivity(
                    context,
                    0,
                    MainActivity.transactionIntent(context, transaction.id)
                        .setData("tally://capture/${transaction.id}/split".toUri()),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(0, "Ignore", action(context, CaptureActionReceiver.ACTION_IGNORE, transaction.id))
            .build()
        NotificationManagerCompat.from(context).notify(transaction.id, NOTIFICATION_ID, notification)
    }

    /** Whether the capture's notification is still in the shade (not swiped away or handled). */
    fun isShowing(context: Context, transactionId: String): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.tag == transactionId && it.id == NOTIFICATION_ID }

    /** Clears notifications for captures handled elsewhere (the dashboard, another phone). */
    suspend fun dismissHandled(context: Context, stillNeedsReview: suspend (transactionId: String) -> Boolean) {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag != null }
            .forEach { if (!stillNeedsReview(it.tag)) cancel(context, it.tag) }
    }

    fun cancel(context: Context, transactionId: String) {
        NotificationManagerCompat.from(context).cancel(transactionId, NOTIFICATION_ID)
    }

    private fun action(context: Context, action: String, transactionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CaptureActionReceiver::class.java)
                .setAction(action)
                .setData("tally://capture/$transactionId/$action".toUri())
                .putExtra(CaptureActionReceiver.EXTRA_TRANSACTION_ID, transactionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
