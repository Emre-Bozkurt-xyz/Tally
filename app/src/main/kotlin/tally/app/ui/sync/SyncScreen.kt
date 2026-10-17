package tally.app.ui.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tally.app.sync.ServerSettings
import tally.app.sync.SyncManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(sync: SyncManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by sync.settings.server.collectAsStateWithLifecycle()
    val status by sync.settings.status.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(saved.url) }
    var clientId by remember { mutableStateOf(saved.clientId) }
    var clientSecret by remember { mutableStateOf(saved.clientSecret) }
    // The settings load asynchronously; fill the fields once they arrive.
    LaunchedEffect(saved) {
        url = saved.url
        clientId = saved.clientId
        clientSecret = saved.clientSecret
    }
    val edited = ServerSettings(url, clientId, clientSecret)
    val dirty = edited != saved
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String) {
        busy = true
        message = null
        scope.launch {
            message = try {
                if (dirty) {
                    sync.settings.setServer(edited)
                    sync.updateSchedule()
                }
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Backs up everything to your Tally server and keeps the web dashboard current. " +
                    "The phone signs in through Cloudflare Access with a service token " +
                    "(Zero Trust, Access, Service credentials). If the server has an Anthropic " +
                    "key, purchases from merchants Tally doesn't know yet get a suggested " +
                    "category (only the merchant name, amount and your category names are sent).",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Client secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && edited.isComplete,
                    onClick = {
                        run {
                            val me = sync.transport.whoAmI()
                            "Connected as ${me.name}" + if (me.kind == "SERVICE_TOKEN") " (service token)" else ""
                        }
                    },
                ) { Text("Test connection") }
                Button(
                    enabled = !busy && edited.isComplete,
                    onClick = {
                        run {
                            when (val outcome = sync.syncNow()) {
                                is SyncManager.Outcome.Synced -> outcome.summary()
                                is SyncManager.Outcome.Failed -> outcome.message
                            }
                        }
                    },
                ) { Text("Sync now") }
            }
            if (dirty && !busy) {
                OutlinedButton(onClick = { run { "Saved" } }) { Text("Save") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("Status", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            val lastSuccess = status.lastSuccessAt
            Text(
                if (lastSuccess == null) "Never synced" else "Last synced ${relative(lastSuccess)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            status.lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            status.lastError?.let { error ->
                Text(
                    "Last attempt ${status.lastAttemptAt?.let(::relative).orEmpty()} failed: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Syncs ${SyncManager.CHANGE_DELAY_SECONDS} seconds after changes, right away when " +
                    "the dashboard or another device changes something while Tally is open, " +
                    "and hourly in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun relative(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
