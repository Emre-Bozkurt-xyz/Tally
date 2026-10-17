package tally.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tally.core.sync.SyncChange
import tally.core.sync.SyncRequest
import tally.core.sync.SyncServer

/**
 * Runs against a real Postgres when TALLY_TEST_DATABASE_URL is set (CI provides one; locally:
 * `docker run --rm -d -p 55432:5432 -e POSTGRES_USER=tally -e POSTGRES_PASSWORD=test postgres:17-alpine`
 * and TALLY_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/tally). Skipped otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TALLY_TEST_DATABASE_URL", matches = ".+")
class PostgresSyncStoreTest {
    private val database = Database(
        ServerConfig(
            port = 0,
            databaseUrl = System.getenv("TALLY_TEST_DATABASE_URL"),
            databaseUser = System.getenv("TALLY_TEST_DATABASE_USER") ?: "tally",
            databasePassword = System.getenv("TALLY_TEST_DATABASE_PASSWORD") ?: "test",
            access = null,
        ),
    ).also { it.migrate() }
    private val store = PostgresSyncStore(database.dataSource)
    private val server = SyncServer(store)
    private val t = System.currentTimeMillis()

    @BeforeEach
    fun empty() {
        database.dataSource.connection.use {
            it.createStatement().use { s -> s.execute("TRUNCATE sync_rows; ALTER SEQUENCE sync_seq RESTART") }
        }
    }

    @AfterAll
    fun close() = database.close()

    private fun row(id: String, updatedAt: Long, note: String = "x", deleted: Boolean = false) =
        SyncChange("transactions", id, updatedAt, deleted, JsonObject(mapOf("note" to JsonPrimitive(note), "nested" to JsonObject(mapOf("n" to JsonPrimitive(1))))))

    private fun sync(since: Long, vararg changes: SyncChange) = server.handle(SyncRequest("phone", since, changes.toList()))

    @Test
    fun `rows round trip through jsonb with a cursor`() {
        val response = sync(0, row("a", t - 10), row("b", t - 5))
        assertEquals(listOf(row("a", t - 10), row("b", t - 5)), response.changes)
        assertTrue(response.cursor > 0)
        assertTrue(sync(response.cursor).changes.isEmpty())
        assertEquals(setOf(row("a", t - 10), row("b", t - 5)), store.current("transactions").toSet())
        assertTrue(store.current("rules").isEmpty())
        assertEquals(response.cursor, store.latestSeq())
    }

    @Test
    fun `later write wins and the loser gets the winner`() {
        // Sequence numbers can have gaps (skipped upserts still draw one); only their order matters.
        val cursor = sync(0, row("a", t - 10, note = "new")).cursor
        assertEquals(listOf(row("a", t - 10, note = "new")), sync(cursor, row("a", t - 20, note = "old")).changes)
        // An echo (same updatedAt) is a no-op, not a conflict.
        assertTrue(sync(cursor, row("a", t - 10, note = "new")).changes.isEmpty())

        val newer = sync(cursor, row("a", t - 1, note = "newest", deleted = true))
        assertEquals(row("a", t - 1, note = "newest", deleted = true), newer.changes.single())
        assertTrue(newer.cursor > cursor)
        assertTrue(sync(newer.cursor).changes.isEmpty())
    }
}
