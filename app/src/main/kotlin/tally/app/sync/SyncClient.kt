package tally.app.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tally.app.data.TallyDatabase
import tally.core.sync.SyncChange
import tally.core.sync.SyncProtocol
import tally.core.sync.SyncRequest
import tally.core.sync.SyncResponse
import java.time.Clock

/** Talks to the server's `/api/sync`. HTTP in the app, the in-process server in tests. */
fun interface SyncTransport {
    suspend fun exchange(request: SyncRequest): SyncResponse
}

/**
 * Where a sync left off. [cursor] is the server's position (rows after it are new to this
 * phone); [watermark] is when the last successful sync started (rows changed since then
 * haven't been pushed). -1 means never: push everything.
 */
data class SyncCheckpoint(val cursor: Long = 0, val watermark: Long = -1)

interface SyncCheckpointStore {
    suspend fun load(): SyncCheckpoint
    suspend fun save(checkpoint: SyncCheckpoint)
}

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val rejected: List<String>,
    /** Tables that received newer rows from the server (edits from the dashboard or another phone). */
    val pulledTables: Set<String> = emptySet(),
)

/**
 * One sync: push local rows changed since the last sync, then pull everything the server
 * has after our cursor. Last write wins per row, by `updatedAt`, on both ends. Safe to
 * interrupt at any point: the cursor only advances once its rows are stored locally, and
 * pushing a row twice is a no-op.
 */
class SyncClient(
    private val db: TallyDatabase,
    private val transport: SyncTransport,
    private val checkpoints: SyncCheckpointStore,
    private val deviceId: suspend () -> String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val tables = syncTables(db.syncDao()).associateBy { it.name }
    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        val started = clock.millis()
        val device = deviceId()
        var checkpoint = checkpoints.load()
        // Overlap, so a write that read the clock just before the last sync started but
        // committed after it had read its rows still goes up. The server ignores repeats.
        val since = if (checkpoint.watermark < 0) Long.MIN_VALUE else checkpoint.watermark - OVERLAP_MS
        val outgoing = tables.values.flatMap { it.changedSince(since) }

        var pulled = 0
        val pulledTables = mutableSetOf<String>()
        val rejected = mutableListOf<String>()
        val batches = outgoing.chunked(SyncProtocol.PUSH_BATCH).ifEmpty { listOf(emptyList()) }
        var hasMore = true
        var index = 0
        while (index < batches.size || hasMore) {
            val batch = batches.getOrElse(index++) { emptyList() }
            val response = transport.exchange(SyncRequest(device, checkpoint.cursor, batch))
            val applied = applyIncoming(response.changes)
            pulled += applied.size
            pulledTables += applied
            rejected += response.rejected
            checkpoint = checkpoint.copy(cursor = response.cursor)
            checkpoints.save(checkpoint)
            hasMore = response.hasMore
        }
        checkpoints.save(checkpoint.copy(watermark = started))
        SyncResult(pushed = outgoing.size - rejected.size, pulled = pulled, rejected = rejected, pulledTables = pulledTables)
    }

    /** Stores rows that are newer than the local copy. Returns the table of each one stored. */
    private suspend fun applyIncoming(changes: List<SyncChange>): List<String> {
        if (changes.isEmpty()) return emptyList()
        return db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val newer = changes.filter { change ->
                    val table = tables[change.table] ?: return@filter false
                    val local = table.localUpdatedAt(change.id)
                    local == null || change.updatedAt > local
                }
                newer.groupBy { it.table }.forEach { (name, rows) -> tables.getValue(name).apply(rows) }
                newer.map { it.table }
            }
        }
    }

    companion object {
        const val OVERLAP_MS = 60_000L
    }
}
