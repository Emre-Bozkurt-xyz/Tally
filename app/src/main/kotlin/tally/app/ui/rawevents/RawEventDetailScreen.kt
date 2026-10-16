package tally.app.ui.rawevents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tally.app.data.RawEventEntity
import tally.app.data.RawEventRepository
import tally.core.capture.WatchedPackages
import java.time.Instant
import java.time.ZoneId

private val prettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawEventDetailScreen(
    repository: RawEventRepository,
    id: String,
    onBack: () -> Unit,
) {
    val event by remember(repository, id) { repository.observe(id) }
        .collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event?.let { WatchedPackages.displayName(it.source) } ?: "Raw event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        event?.let { current -> EventBody(current, Modifier.padding(padding)) }
    }
}

@Composable
private fun EventBody(current: RawEventEntity, modifier: Modifier = Modifier) {
    SelectionContainer(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            fields(current).forEach { (label, value) -> Field(label, value) }
            Text(
                "Extras",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                remember(current.extrasJson) { prettyExtras(current.extrasJson) },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun fields(e: RawEventEntity): List<Pair<String, String?>> {
    val zone = ZoneId.of(e.zoneId)
    return listOf(
        "Package" to e.source,
        "Received" to Instant.ofEpochMilli(e.receivedAt).atZone(zone).toString(),
        "Posted" to Instant.ofEpochMilli(e.postedAt).atZone(zone).toString(),
        "Channel" to e.channelId,
        "Key" to e.sbnKey,
        "Group summary" to e.isGroupSummary.toString(),
        "Parse status" to e.parseStatus,
        "Title" to e.title,
        "Text" to e.text,
        "Big text" to e.bigText,
    )
}

private fun prettyExtras(extrasJson: String): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(extrasJson))
}.getOrDefault(extrasJson)
