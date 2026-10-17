package tally.app.quickentry

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tally.app.appContainer
import tally.app.capture.CaptureNotifier
import tally.app.ui.theme.TallyTheme

/**
 * A bottom sheet over whatever is on screen (or the lock screen) for logging an expense
 * in a few taps. Also edits an existing transaction when given its id.
 */
class QuickEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        enableEdgeToEdge()
        val request = QuickEntryRequest(
            categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID),
            amountCents = intent.getLongExtra(EXTRA_AMOUNT_CENTS, 0),
            transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID),
        )
        setContent {
            TallyTheme {
                QuickEntrySheet(
                    ledger = appContainer.ledger,
                    request = request,
                    onFinished = { message ->
                        if (message != null) {
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            // Edited from the capture notification (or the ledger): it's handled.
                            request.transactionId?.let { CaptureNotifier.cancel(this, it) }
                        }
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_CATEGORY_ID = "tally.app.extra.CATEGORY_ID"
        private const val EXTRA_AMOUNT_CENTS = "tally.app.extra.AMOUNT_CENTS"
        private const val EXTRA_TRANSACTION_ID = "tally.app.extra.TRANSACTION_ID"

        fun intent(
            context: Context,
            categoryId: String? = null,
            amountCents: Long? = null,
            transactionId: String? = null,
        ): Intent = Intent(context, QuickEntryActivity::class.java).apply {
            categoryId?.let { putExtra(EXTRA_CATEGORY_ID, it) }
            amountCents?.let { putExtra(EXTRA_AMOUNT_CENTS, it) }
            transactionId?.let { putExtra(EXTRA_TRANSACTION_ID, it) }
        }
    }
}

/** What the sheet opens with: a preselected category, a prefilled amount, or a transaction to edit. */
data class QuickEntryRequest(
    val categoryId: String? = null,
    val amountCents: Long = 0,
    val transactionId: String? = null,
)
