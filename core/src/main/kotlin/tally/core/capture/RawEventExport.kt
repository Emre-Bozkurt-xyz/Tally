package tally.core.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * File format of the app's "Export raw events" button. Exported files are dropped into
 * `core/src/test/resources/fixtures/` and read back by parser tests, so the app and the
 * tests share this one definition.
 *
 * Scrub card numbers beyond the last 4 digits before committing an export.
 */
@Serializable
data class RawEventExport(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val events: List<ExportedRawEvent>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class ExportedRawEvent(
    val id: String,
    /** Package name, or `csv:<file>` for statement rows. */
    val source: String,
    /** Epoch ms when Tally received the notification. */
    val receivedAt: Long,
    /** Epoch ms from `StatusBarNotification.postTime`. */
    val postedAt: Long,
    val zoneId: String,
    val sbnKey: String? = null,
    val channelId: String? = null,
    val isGroupSummary: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
) {
    fun toRawNotification() = RawNotification(
        packageName = source,
        title = title,
        text = text,
        bigText = bigText,
        postedAt = Instant.ofEpochMilli(postedAt),
    )
}

object RawEventExportCodec {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(export: RawEventExport): String = json.encodeToString(RawEventExport.serializer(), export)

    fun decode(text: String): RawEventExport = json.decodeFromString(RawEventExport.serializer(), text)
}
