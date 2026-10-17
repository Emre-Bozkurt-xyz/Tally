package tally.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.app.data.CaptureMemory
import tally.app.data.CapturePipeline
import tally.app.data.CategoryRepository
import tally.app.data.DefaultData
import tally.app.data.LedgerRepository
import tally.app.data.RawEventEntity
import tally.app.data.TallyDatabase
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncRequest
import tally.core.sync.SyncResponse
import tally.core.sync.SyncServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Two phones against the real sync rules (in-process server), with JSON on the wire. */
@RunWith(RobolectricTestRunner::class)
class SyncClientTest {
    private val clock = TestClock(Instant.parse("2026-10-16T13:30:00Z"))
    private val server = SyncServer(InMemorySyncStore(), clock)
    private val wire = Json { ignoreUnknownKeys = true }
    private val transport = SyncTransport { request ->
        val body = wire.encodeToString(SyncRequest.serializer(), request)
        val response = server.handle(wire.decodeFromString(SyncRequest.serializer(), body))
        wire.decodeFromString(SyncResponse.serializer(), wire.encodeToString(SyncResponse.serializer(), response))
    }
    private val phones = mutableListOf<Phone>()

    private inner class Phone(val name: String) {
        val db: TallyDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TallyDatabase::class.java,
        ).allowMainThreadQueries().build()
        val ledger = LedgerRepository(db, clock)
        val categories = CategoryRepository(db, clock)
        val capture = CapturePipeline(db, CaptureMemory(db, clock), clock)
        val checkpoints = MemoryCheckpoints()
        val client = SyncClient(db, transport, checkpoints, { name }, clock)

        suspend fun sync(): SyncResult = client.sync().also { clock.advance(Duration.ofMinutes(5)) }

        init { phones += this }
    }

    @After
    fun close() = phones.forEach { it.db.close() }

    private suspend fun phone(name: String) = Phone(name).also { DefaultData.seedIfEmpty(it.db) }

    private fun rawEvent(source: String, title: String, text: String) = RawEventEntity(
        id = UUID.randomUUID().toString(), source = source,
        receivedAt = clock.millis(), postedAt = clock.millis(), zoneId = "America/Toronto",
        sbnKey = null, channelId = null, isGroupSummary = false, title = title, text = text,
        bigText = null, extrasJson = "{}", parseStatus = ParseStatus.PENDING.name, transactionId = null,
        updatedAt = clock.millis(),
    )

    @Test
    fun `a new phone restores everything, and private notifications never leave`() = runTest {
        val a = phone("a")
        val coffee = DefaultData.categoryId("Food/Coffee")
        a.categories.setBudget(coffee, 60_00)
        val manual = a.ledger.saveQuickEntry(null, 425, coffee, DefaultData.accountId("Cash"), note = "flat white")
        a.ledger.addTag(manual.id, "work")
        a.ledger.addSplit(manual.id, 200, null, owedBy = "Sam")
        val wallet = rawEvent(WatchedPackages.GOOGLE_WALLET, "FOODIE FRUITIE", "CA\$34.32 with Royal Bank Debit/Débit ••3881")
        a.db.rawEventDao().insert(wallet)
        val captured = a.capture.process(wallet) as CapturePipeline.Outcome.Captured
        a.db.rawEventDao().insert(rawEvent("com.whatsapp", "Mom", "call me"))

        val first = a.sync()
        assertTrue(first.pushed > 0)
        assertTrue(first.rejected.isEmpty())

        val b = phone("b")
        b.sync()
        // The fresh phone's seeded categories (updatedAt 0) lose to the real edit.
        assertEquals(60_00L, b.db.categoryDao().getActive().single { it.id == coffee }.monthlyBudgetCents)
        assertEquals("flat white", b.ledger.getTransaction(manual.id)?.note)
        assertEquals(listOf("work"), b.db.syncDao().tagsSince(Long.MIN_VALUE).map { it.name })
        assertEquals(1, b.db.syncDao().transactionTagsSince(Long.MIN_VALUE).size)
        assertEquals("Sam", b.db.syncDao().splitsSince(Long.MIN_VALUE).single().owedBy)
        val restored = b.ledger.getTransaction(captured.transaction.id)
        assertNotNull(restored)
        assertEquals(restored!!.merchantId?.let { b.db.merchantDao().getById(it)?.name }, "Foodie Fruitie")
        val events = b.db.rawEventDao().getAll()
        assertEquals(listOf(WatchedPackages.GOOGLE_WALLET), events.map { it.source })
        assertEquals(ParseStatus.PARSED.name, events.single().parseStatus)
    }

    @Test
    fun `edits travel both ways and the later write wins`() = runTest {
        val a = phone("a")
        val b = phone("b")
        val coffee = DefaultData.categoryId("Food/Coffee")
        val t = a.ledger.saveQuickEntry(null, 425, coffee, null, note = null)
        a.sync()
        b.sync()

        // Both edit offline; b's edit is later.
        a.ledger.saveQuickEntry(a.ledger.getTransaction(t.id), 500, coffee, null, note = "from a")
        clock.advance(Duration.ofSeconds(10))
        b.ledger.saveQuickEntry(b.ledger.getTransaction(t.id), 450, coffee, null, note = "from b")
        a.sync()
        b.sync()
        a.sync()
        assertEquals("from b", a.ledger.getTransaction(t.id)?.note)
        assertEquals("from b", b.ledger.getTransaction(t.id)?.note)

        // A delete is just a newer write.
        b.ledger.deleteTransaction(t.id)
        b.sync()
        a.sync()
        assertEquals(null, a.ledger.getTransaction(t.id))
    }

    @Test
    fun `a quiet second sync sends only the overlap and receives nothing`() = runTest {
        val a = phone("a")
        a.ledger.saveQuickEntry(null, 425, DefaultData.categoryId("Food/Coffee"), null, note = null)
        // Older than the overlap by the next sync.
        clock.advance(Duration.ofMinutes(2))
        a.sync()
        clock.advance(Duration.ofHours(1))
        val again = a.sync()
        assertEquals(SyncResult(pushed = 0, pulled = 0, rejected = emptyList()), again)
        assertFalse(a.checkpoints.saved.watermark < 0)
    }

    private class MemoryCheckpoints : SyncCheckpointStore {
        var saved = SyncCheckpoint()
        override suspend fun load() = saved
        override suspend fun save(checkpoint: SyncCheckpoint) { saved = checkpoint }
    }

    private class TestClock(var now: Instant, private val zone: ZoneId = ZoneId.of("America/Toronto")) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
        override fun instant(): Instant = now
        fun advance(by: Duration) { now += by }
    }
}
