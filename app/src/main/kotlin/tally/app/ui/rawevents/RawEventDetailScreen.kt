package tally.app.ui.rawevents

import tally.app.ui.components.TallyTopBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.TallyCard
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

@Composable
fun RawEventDetailScreen(
    repository: RawEventRepository,
    id: String,
    onBack: () -> Unit,
) {
    val event by remember(repository, id) { repository.observe(id) }
        .collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar(event?.let { WatchedPackages.displayName(it.source) } ?: "Raw event", onBack)
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
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TallyCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                fields(current).forEach { (label, value) -> Field(label, value) }
            }
            TallyCard(Modifier.fillMaxWidth()) {
                MonoLabel("Extras")
                Text(
                    remember(current.extrasJson) { prettyExtras(current.extrasJson) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                    modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MonoLabel(label)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, color = if (value == null) Tally.colors.faint else MaterialTheme.colorScheme.onSurface)
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
