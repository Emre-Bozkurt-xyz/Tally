package tally.app.quickentry

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import tally.app.R

/**
 * The "Quick add" entry in the launcher's long-press menu. Published at runtime rather than
 * from a static shortcuts.xml, because static shortcuts hard-code the package name and debug
 * builds use a different one (`dev.emre.tally.debug`).
 */
object QuickAddShortcut {
    private const val ID = "quick-add"

    /** Adds or refreshes the shortcut. `pushDynamicShortcut` is not rate-limited. */
    fun publish(context: Context) {
        val label = context.getString(R.string.quick_add)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_quick_add))
            .setIntent(QuickEntryActivity.intent(context).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }
}
