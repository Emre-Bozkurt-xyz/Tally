package tally.app.ui.rawevents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tally.app.data.RawEventEntity
import tally.app.data.RawEventRepository
import tally.app.ui.components.ActionSheet
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.ConfirmSheet
import tally.app.ui.components.EmptyMessage
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillTone
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.SheetAction
import tally.app.ui.components.StatusPill
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.groupShape
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm:ss")

/** Every notification the listener recorded, newest first: export, clear, re-parse. */
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
    var actions by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var reparsing by remember { mutableStateOf(false) }
    val watchedCount = events.count { it.source in WatchedPackages.ALL }
    val otherCount = events.size - watchedCount

    fun export(everything: Boolean) {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar("Raw events", onBack) {
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
                CircleIconButton(TallyIcons.Download, "Export or clear", { actions = true }, enabled = events.isNotEmpty() && !exporting)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            if (events.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        EmptyMessage(
                            "No notifications yet",
                            "Grant notification access in Setup, then make a purchase, or turn on \"Log all packages\" to see everything that arrives.",
                        )
                        PillButton("Open setup", onOpenSetup, icon = TallyIcons.Sliders)
                    }
                }
            } else {
                item {
                    SectionHeader("$watchedCount bank & wallet · $otherCount other")
                }
            }
            itemsIndexed(events, key = { _, it -> it.id }) { index, event ->
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    RawEventRow(event, Modifier.clip(groupShape(index, events.size)), onClick = { onOpenEvent(event.id) })
                    if (index < events.lastIndex) RowDivider(start = 18.dp)
                }
            }
        }
    }

    if (actions) {
        ActionSheet(
            title = "Raw events",
            actions = listOfNotNull(
                SheetAction("Export bank & wallet ($watchedCount)", TallyIcons.Download) { export(everything = false) },
                SheetAction("Export everything (${events.size})", TallyIcons.Download) { export(everything = true) },
                SheetAction("Setup", TallyIcons.Sliders, onClick = onOpenSetup),
                if (otherCount > 0) SheetAction("Clear other apps ($otherCount)", TallyIcons.Trash, danger = true) { confirmClear = true } else null,
            ),
            onDismiss = { actions = false },
        )
    }
    if (confirmClear) {
        ConfirmSheet(
            title = "Clear $otherCount notifications?",
            body = "Deletes everything recorded from apps other than RBC and the wallets (what \"Log all packages\" collects). " +
                "Bank and wallet notifications are kept. Turn off \"Log all packages\" in Setup to stop collecting them.",
            confirm = "Clear",
            onConfirm = { scope.launch { snackbar.showSnackbar("Cleared ${repository.deleteUnwatched()} notifications") } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun RawEventRow(event: RawEventEntity, modifier: Modifier, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(event.receivedAt).atZone(ZoneId.of(event.zoneId))
    val watched = event.source in WatchedPackages.ALL
    Column(
        modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoLabel("${WatchedPackages.displayName(event.source)} · ${TIME_FORMAT.format(time)}", Modifier.weight(1f, fill = false))
            if (event.isGroupSummary) StatusPill("Summary")
            if (watched) {
                StatusPill(
                    event.parseStatus.lowercase(),
                    tone = when (event.parseStatus) {
                        ParseStatus.PARSED.name -> PillTone.Positive
                        ParseStatus.FAILED.name -> PillTone.Danger
                        else -> PillTone.Neutral
                    },
                )
            }
        }
        Text(event.title ?: "(no title)", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            event.bigText ?: event.text ?: "(no text)",
            style = MaterialTheme.typography.bodySmall,
            color = Tally.colors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
