package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.core.capture.RawEventExportCodec
import tally.core.model.ParseStatus
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RawEventRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repo = RawEventRepository(db.rawEventDao())

    @After
    fun close() = db.close()

    @Test
    fun `drops identical re-post within the window`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0)))
        assertFalse(repo.record(event(receivedAt = T0 + 30_000)))
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `stores identical re-post after the window`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0)))
        assertTrue(repo.record(event(receivedAt = T0 + RawEventRepository.DUPLICATE_WINDOW_MS + 1)))
    }

    @Test
    fun `stores an update to the same notification with new text`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0, text = "Purchase of \$4.25")))
        assertTrue(repo.record(event(receivedAt = T0 + 1_000, text = "Purchase of \$5.00")))
    }

    @Test
    fun `null fields compare equal when deduping`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0, title = null, text = null)))
        assertFalse(repo.record(event(receivedAt = T0 + 1_000, title = null, text = null)))
    }

    @Test
    fun `null and non-null text are different`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0, text = null)))
        assertTrue(repo.record(event(receivedAt = T0 + 1_000, text = "now has text")))
    }

    @Test
    fun `events without a key are never deduped`() = runTest {
        assertTrue(repo.record(event(receivedAt = T0, sbnKey = null)))
        assertTrue(repo.record(event(receivedAt = T0 + 1_000, sbnKey = null)))
    }

    @Test
    fun `source stats count events and track the latest`() = runTest {
        repo.record(event(source = "a", receivedAt = T0, sbnKey = "1"))
        repo.record(event(source = "a", receivedAt = T0 + 5_000, sbnKey = "2"))
        repo.record(event(source = "b", receivedAt = T0 + 1_000, sbnKey = "3"))
        val stats = repo.observeSourceStats().first().associateBy { it.source }
        assertEquals(SourceStats("a", 2, T0 + 5_000), stats["a"])
        assertEquals(SourceStats("b", 1, T0 + 1_000), stats["b"])
    }

    @Test
    fun `deleting unwatched keeps bank and wallet events`() = runTest {
        repo.record(event(source = "com.rbc.mobile.android", receivedAt = T0, sbnKey = "1"))
        repo.record(event(source = "com.huawei.health", receivedAt = T0, sbnKey = "2"))
        repo.record(event(source = "com.discord", receivedAt = T0, sbnKey = "3"))

        assertEquals(2, repo.deleteUnwatched())
        assertEquals(listOf("com.rbc.mobile.android"), repo.observeAll().first().map { it.source })
    }

    @Test
    fun `export round trips through the core format`() = runTest {
        repo.record(event(receivedAt = T0 + 1, sbnKey = "2", extrasJson = """{"android.title":"Hi"}"""))
        repo.record(event(receivedAt = T0, sbnKey = "1", extrasJson = "not json"))

        val export = RawEventExportCodec.decode(repo.exportJson(nowMs = T0 + 10))

        assertEquals(T0 + 10, export.exportedAt)
        // Oldest first, and unparseable extras degrade to an empty object.
        assertEquals(listOf("1", "2"), export.events.map { it.sbnKey })
        assertTrue(export.events[0].extras.isEmpty())
        assertEquals(JsonPrimitive("Hi"), export.events[1].extras["android.title"])
    }

    private fun event(
        receivedAt: Long,
        source: String = "com.rbc.mobile.android",
        sbnKey: String? = "0|com.rbc.mobile.android|1|null|10123",
        title: String? = "Credit card purchase",
        text: String? = "A purchase of \$12.34 was made at TIM HORTONS",
        extrasJson: String = "{}",
    ) = RawEventEntity(
        id = UUID.randomUUID().toString(),
        source = source,
        receivedAt = receivedAt,
        postedAt = receivedAt,
        zoneId = "America/Toronto",
        sbnKey = sbnKey,
        channelId = null,
        isGroupSummary = false,
        title = title,
        text = text,
        bigText = null,
        extrasJson = extrasJson,
        parseStatus = ParseStatus.PENDING.name,
        transactionId = null,
    )

    private companion object {
        const val T0 = 1_760_000_000_000L
    }
}
