package tally.app.quickentry

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch
import tally.app.R
import tally.app.appContainer

/** Quick Settings tile: swipe down, tap, and the quick-entry sheet opens (even when locked). */
class QuickEntryTileService : TileService() {
    override fun onTileAdded() = recordAdded(true)

    override fun onTileRemoved() = recordAdded(false)

    override fun onStartListening() {
        // Only called while the tile is in the panel; covers tiles added before tracking existed.
        recordAdded(true)
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

    private fun recordAdded(added: Boolean) {
        val container = appContainer
        container.appScope.launch { container.settings.setQuickAddTileAdded(added) }
    }

    companion object {
        /**
         * Shows the system "Add tile to Quick Settings?" prompt. Must be called while the app is
         * in the foreground.
         */
        fun requestAdd(context: Context) {
            val container = context.appContainer
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, QuickEntryTileService::class.java),
                context.getString(R.string.quick_add),
                Icon.createWithResource(context, R.drawable.ic_quick_add),
                context.mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                ) {
                    container.appScope.launch { container.settings.setQuickAddTileAdded(true) }
                }
            }
        }
    }
}
