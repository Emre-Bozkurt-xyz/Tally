package tally.app.ui.rawevents

import tally.app.ui.theme.TallyIcons
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.TallyTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    /** Re-runs unprocessed bank and wallet events through the parsers; returns a summary. */
    onReparse: suspend () -> String,
    onBack: () -> Unit,
) {
    val events by remember(repository) { repository.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val watchedCount = events.count { it.source in WatchedPackages.ALL }
    val otherCount = events.size - watchedCount

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear $otherCount notifications?") },
            text = {
                Text(
                    "Deletes everything recorded from apps other than RBC and the wallets (what " +
                        "\"Log all packages\" collects). Bank and wallet notifications are kept. " +
                        "Turn off \"Log all packages\" in Setup to stop collecting them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { snackbar.showSnackbar("Cleared ${repository.deleteUnwatched()} notifications") }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    var reparsing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TallyTopBar("Raw events (${events.size})", onBack) {
                    fun export(everything: Boolean) {
                        exportMenu = false
                        scope.launch {
                            exporting = true
                            try {
                                shareJson(context, exportRawEvents(context, repository, everything))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Export failed: ${e.message}")
                            } finally {
                                exporting = false
                            }
                        }
                    }
                    Box {
                        CircleIconButton(TallyIcons.Download, "Export or clear", { exportMenu = true }, enabled = events.isNotEmpty() && !exporting)
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export bank & wallet ($watchedCount)") },
                                onClick = { export(everything = false) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export everything (${events.size})") },
                                onClick = { export(everything = true) },
                            )
                            if (otherCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Clear other apps ($otherCount)", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        exportMenu = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    }
                    CircleIconButton(
                        TallyIcons.Sync,
                        "Re-parse",
                        enabled = !reparsing,
                        onClick = {
                            scope.launch {
                                reparsing = true
                                try {
                                    snackbar.showSnackbar(onReparse())
                                } finally {
                                    reparsing = false
                                }
                            }
                        },
                    )
                    CircleIconButton(TallyIcons.Sliders, "Setup", onOpenSetup)
            }
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
        if (event.source in WatchedPackages.ALL) append(" · ${event.parseStatus.lowercase()}")
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
