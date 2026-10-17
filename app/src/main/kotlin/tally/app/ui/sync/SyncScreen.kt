package tally.app.ui.sync

import tally.app.ui.components.TallyTopBar
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import tally.app.ui.components.FieldLabel
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillStyle
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SoftTextField
import tally.app.ui.components.TallyCard
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TallyTopBar("Sync", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val lastSuccess = status.lastSuccessAt
            val failing = status.lastError != null && (lastSuccess == null || (status.lastAttemptAt ?: 0) > lastSuccess)
            val colors = Tally.colors
            TallyCard(Modifier.fillMaxWidth(), color = colors.hero, shape = RoundedCornerShape(28.dp), padding = PaddingValues(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (failing) colors.over else if (lastSuccess != null) colors.heroAccent else colors.onHeroMuted),
                    )
                    Spacer(Modifier.width(8.dp))
                    MonoLabel(if (failing) "Last sync failed" else if (lastSuccess != null) "Connected" else "Not set up", color = colors.onHeroMuted)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (lastSuccess == null) "Never synced" else "Synced ${relative(lastSuccess)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onHero,
                )
                status.lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onHeroMuted, modifier = Modifier.padding(top = 4.dp)) }
                if (failing) {
                    Text(
                        "${status.lastAttemptAt?.let(::relative).orEmpty()}: ${status.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.over,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "Sync now",
                        enabled = !busy && edited.isComplete,
                        onClick = {
                            run {
                                when (val outcome = sync.syncNow()) {
                                    is SyncManager.Outcome.Synced -> outcome.summary()
                                    is SyncManager.Outcome.Failed -> outcome.message
                                }
                            }
                        },
                        style = PillStyle.Accent,
                        icon = TallyIcons.Sync,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton(
                        "Test",
                        enabled = !busy && edited.isComplete,
                        onClick = {
                            run {
                                val me = sync.transport.whoAmI()
                                "Connected as ${me.name}" + if (me.kind == "SERVICE_TOKEN") " (service token)" else ""
                            }
                        },
                        style = PillStyle.Soft,
                        modifier = Modifier.weight(1f),
                    )
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onHero, modifier = Modifier.padding(top = 12.dp)) }
            }

            Text("Server", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            FieldLabel("Server URL")
            SoftTextField(url, { url = it }, "https://tally.example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            FieldLabel("Access client ID")
            SoftTextField(clientId, { clientId = it }, "….access")
            FieldLabel("Access client secret")
            SoftTextField(
                clientSecret,
                { clientSecret = it },
                "Secret",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
            if (dirty && !busy) {
                PillButton("Save", { run { "Saved" } }, icon = TallyIcons.Check, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }

            Text(
                "Backs up everything to your Tally server and keeps the web dashboard current. The phone signs in " +
                    "through Cloudflare Access with a service token (Zero Trust, Access, Service credentials). " +
                    "Syncs ${SyncManager.CHANGE_DELAY_SECONDS} seconds after changes, right away when the dashboard " +
                    "changes something while Tally is open, and hourly in the background. With an Anthropic key on " +
                    "the server, new merchants get a suggested category (only the merchant name, amount and your " +
                    "category names are sent).",
                style = MaterialTheme.typography.bodySmall,
                color = Tally.colors.muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun relative(at: Long): String {
    val now = System.currentTimeMillis()
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}
