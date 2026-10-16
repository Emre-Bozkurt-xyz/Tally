package tally.app.ui.setup

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import tally.app.capture.TallyNotificationListener

/** One-time permissions a sideloaded build needs; re-checked every time the screen resumes. */
data class SetupStatus(
    val listenerEnabled: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
) {
    companion object {
        fun read(context: Context): SetupStatus {
            val power = context.getSystemService(PowerManager::class.java)
            return SetupStatus(
                listenerEnabled = context.packageName in
                    NotificationManagerCompat.getEnabledListenerPackages(context),
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

object SetupIntents {
    fun notificationAccess(context: Context): List<Intent> {
        val component = ComponentName(context, TallyNotificationListener::class.java)
        return listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
    }

    /** "Allow restricted settings" lives in this screen's three-dot menu. */
    fun appDetails(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    fun appNotifications(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) + appDetails(context)

    // Play policy restricts this permission; Tally is sideloaded and needs it to keep capturing.
    @SuppressLint("BatteryLife")
    fun batteryOptimization(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
    ) + appDetails(context)
}

/** Starts the first intent that resolves. */
fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}
