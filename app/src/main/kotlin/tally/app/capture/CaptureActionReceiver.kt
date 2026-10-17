package tally.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import tally.app.appContainer

/** Handles the capture notification's tap (confirm) and its Ignore action. */
class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TRANSACTION_ID) ?: return
        val container = context.appContainer
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_CONFIRM -> container.ledger.confirmCapture(id)
                    ACTION_IGNORE -> container.ledger.deleteTransaction(id)
                }
                CaptureNotifier.cancel(context, id)
            } catch (t: Throwable) {
                Log.e(TAG, "Capture action ${intent.action} failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "tally.app.action.CONFIRM_CAPTURE"
        const val ACTION_IGNORE = "tally.app.action.IGNORE_CAPTURE"
        const val EXTRA_TRANSACTION_ID = "tally.app.extra.TRANSACTION_ID"
        private const val TAG = "CaptureAction"
    }
}
