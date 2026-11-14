package tally.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import tally.app.appContainer
import tally.core.model.Money
import tally.core.report.shareOf

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
                    ACTION_SPLIT_HALF -> {
                        val person = intent.getStringExtra(EXTRA_PERSON) ?: return@launch
                        val ledger = container.ledger
                        val transaction = ledger.getTransaction(id) ?: return@launch
                        val half = shareOf(transaction.amountCents, 2)
                        ledger.splitWith(id, person, half).onSuccess {
                            // Still to confirm: show it again, saying who owes what.
                            ledger.captured(id)?.let { captured ->
                                CaptureNotifier.onCaptured(context, captured.copy(owedNote = "${person.substringBefore(' ')} owes ${Money.format(half)}"))
                            }
                            return@launch
                        }
                    }
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
        const val ACTION_SPLIT_HALF = "tally.app.action.SPLIT_HALF"
        const val EXTRA_PERSON = "tally.app.extra.PERSON"
        const val EXTRA_TRANSACTION_ID = "tally.app.extra.TRANSACTION_ID"
        private const val TAG = "CaptureAction"
    }
}
