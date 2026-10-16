package tally.app.quickentry

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: swipe down, tap, and the quick-entry sheet opens (even when locked). */
class QuickEntryTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = QuickEntryActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The PendingIntent overload is required from API 34.
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }
}
