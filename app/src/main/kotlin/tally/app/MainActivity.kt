package tally.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tally.app.ui.TallyNavHost
import tally.app.ui.theme.TallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TallyTheme {
                TallyNavHost(container = appContainer, openTransactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appContainer.sync.requestSync() }
    }

    companion object {
        private const val EXTRA_TRANSACTION_ID = "tally.app.extra.OPEN_TRANSACTION_ID"

        /** Opens the app on a transaction's detail screen. */
        fun transactionIntent(context: Context, transactionId: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
