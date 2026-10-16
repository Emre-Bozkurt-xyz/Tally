package tally.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tally.core.capture.ExportedRawEvent
import tally.core.capture.RawEventExport
import tally.core.capture.RawEventExportCodec

class RawEventRepository(private val dao: RawEventDao) {
    // Serializes the duplicate check and the insert, so two near-simultaneous re-posts
    // can't both pass the check.
    private val recordLock = Mutex()

    fun observeAll(): Flow<List<RawEventEntity>> = dao.observeAll()

    fun observe(id: String): Flow<RawEventEntity?> = dao.observeById(id)

    fun observeSourceStats(): Flow<List<SourceStats>> = dao.observeSourceStats()

    /**
     * Stores the event unless the same notification (same key and identical text) was
     * already stored within [DUPLICATE_WINDOW_MS]. Apps re-post and update notifications.
     * Returns whether it was stored.
     */
    suspend fun record(event: RawEventEntity): Boolean = recordLock.withLock {
        val key = event.sbnKey
        val duplicate = key != null && dao.existsRecentDuplicate(
            sbnKey = key,
            since = event.receivedAt - DUPLICATE_WINDOW_MS,
            title = event.title,
            text = event.text,
            bigText = event.bigText,
        )
        if (!duplicate) dao.insert(event)
        !duplicate
    }

    suspend fun exportJson(nowMs: Long): String {
        val events = dao.getAll().map { it.toExported() }
        return RawEventExportCodec.encode(RawEventExport(exportedAt = nowMs, events = events))
    }

    private fun RawEventEntity.toExported() = ExportedRawEvent(
        id = id,
        source = source,
        receivedAt = receivedAt,
        postedAt = postedAt,
        zoneId = zoneId,
        sbnKey = sbnKey,
        channelId = channelId,
        isGroupSummary = isGroupSummary,
        title = title,
        text = text,
        bigText = bigText,
        extras = runCatching { Json.parseToJsonElement(extrasJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap())),
    )

    companion object {
        const val DUPLICATE_WINDOW_MS = 60_000L
    }
}
