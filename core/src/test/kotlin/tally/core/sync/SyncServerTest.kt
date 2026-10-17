package tally.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SyncServerTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val server = SyncServer(InMemorySyncStore(), Clock.fixed(now, ZoneOffset.UTC))
    private val t = now.toEpochMilli()

    private fun row(id: String, updatedAt: Long, note: String = "x", table: String = "transactions", deleted: Boolean = false) =
        SyncChange(table, id, updatedAt, deleted, JsonObject(mapOf("note" to JsonPrimitive(note))))

    private fun sync(since: Long, vararg changes: SyncChange) = server.handle(SyncRequest("phone", since, changes.toList()))

    @Test
    fun `first sync pushes rows and gets them back with a cursor`() {
        val response = sync(0, row("a", t - 10), row("b", t - 5))
        assertEquals(listOf("a", "b"), response.changes.map { it.id })
        assertEquals(2L, response.cursor)
        assertFalse(response.hasMore)

        // Nothing new after the cursor.
        assertTrue(sync(response.cursor).changes.isEmpty())
    }

    @Test
    fun `another device sees changes after its cursor only`() {
        val first = sync(0, row("a", t - 10))
        sync(first.cursor, row("b", t - 5))
        assertEquals(listOf("b"), sync(first.cursor).changes.map { it.id })
    }

    @Test
    fun `later write wins, and the loser gets the winner back`() {
        sync(0, row("a", t - 10, note = "new"))
        val stale = sync(1, row("a", t - 20, note = "old"))
        assertEquals(listOf(row("a", t - 10, note = "new")), stale.changes)

        val newer = sync(1, row("a", t - 1, note = "newest"))
        assertEquals("newest", (newer.changes.single().data["note"] as JsonPrimitive).content)
    }

    @Test
    fun `deletes are just newer rows`() {
        val first = sync(0, row("a", t - 10))
        val deleted = sync(first.cursor, row("a", t - 5, deleted = true))
        assertTrue(deleted.changes.single().deleted)
    }

    @Test
    fun `unknown tables and future clocks are refused`() {
        val response = sync(0, row("a", t + SyncProtocol.MAX_FUTURE_SKEW_MS + 1), row("b", t, table = "secrets"))
        assertEquals(listOf("transactions/a", "secrets/b"), response.rejected)
        assertTrue(response.changes.isEmpty())
    }

    @Test
    fun `big histories come in pages`() {
        val rows = (1..SyncProtocol.PAGE_SIZE + 3).map { row("r$it", t - 100_000 + it) }
        val first = sync(0, *rows.toTypedArray())
        assertTrue(first.hasMore)
        assertEquals(SyncProtocol.PAGE_SIZE, first.changes.size)
        val second = sync(first.cursor)
        assertFalse(second.hasMore)
        assertEquals(3, second.changes.size)
    }
}
