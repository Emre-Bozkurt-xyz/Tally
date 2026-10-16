package tally.app.ui.rawevents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tally.app.data.RawEventEntity
import tally.app.data.RawEventRepository
import tally.core.capture.WatchedPackages
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawEventsScreen(
    repository: RawEventRepository,
    onOpenEvent: (String) -> Unit,
    onOpenSetup: () -> Unit,
) {
    val events by remember(repository) { repository.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raw events (${events.size})") },
                actions = {
                    IconButton(
                        enabled = events.isNotEmpty() && !exporting,
                        onClick = {
                            scope.launch {
                                exporting = true
                                try {
                                    shareJson(context, exportRawEvents(context, repository))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Export failed: ${e.message}")
                                } finally {
                                    exporting = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export as JSON")
                    }
                    IconButton(onClick = onOpenSetup) {
                        Icon(Icons.Default.Settings, contentDescription = "Setup")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (events.isEmpty()) {
            EmptyState(onOpenSetup, Modifier.padding(padding))
        } else {
            LazyColumn(contentPadding = padding) {
                items(events, key = { it.id }) { event ->
                    RawEventRow(event, onClick = { onOpenEvent(event.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RawEventRow(event: RawEventEntity, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(event.receivedAt).atZone(ZoneId.of(event.zoneId))
    val overline = buildString {
        append(WatchedPackages.displayName(event.source))
        append(" · ")
        append(TIME_FORMAT.format(time))
        if (event.isGroupSummary) append(" · summary")
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(overline) },
        headlineContent = {
            Text(event.title ?: "(no title)", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                event.bigText ?: event.text ?: "(no text)",
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun EmptyState(onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No notifications captured yet.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Grant notification access in Setup, then make a purchase or turn on " +
                "\"Log all packages\" to see everything that arrives.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onOpenSetup, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open setup")
        }
    }
}
