package tally.core.sync

import java.time.Clock

/** Where the server keeps rows. Postgres in production, in memory for tests. */
interface SyncStore {
    /**
     * Stores [change] if it's newer than what's stored (or new), giving it the next sequence
     * number. Returns the stored row when that one is strictly newer (the caller lost), else null.
     */
    fun applyIfNewer(change: SyncChange): SyncChange?

    /** Rows with sequence numbers above [since], in order, at most [limit]. */
    fun changesSince(since: Long, limit: Int): List<Sequenced>

    /** The current row of every id in [table], deleted ones included. */
    fun current(table: String): List<SyncChange>

    /** The highest sequence number stored, 0 when empty. */
    fun latestSeq(): Long
}

/** Rows were stored: everyone after [cursor] has something to pull. [source] wrote them. */
data class SyncEvent(val cursor: Long, val source: String)

data class Sequenced(val seq: Long, val change: SyncChange)

/**
 * The sync rules, shared by the real server and tests: last write wins per row, the server
 * hands back everything after the client's cursor, and rows from a clock too far in the
 * future are refused. Requests are handled one at a time so sequence numbers commit in order.
 * [onChange] hears about every request that stored something (not echoes), so other devices
 * and open dashboards can pull right away.
 */
class SyncServer(
    private val store: SyncStore,
    private val clock: Clock = Clock.systemUTC(),
    private val onChange: (SyncEvent) -> Unit = {},
) {
    @Synchronized
    fun handle(request: SyncRequest): SyncResponse {
        val before = if (request.changes.isEmpty()) null else store.latestSeq()
        val now = clock.millis()
        val rejected = mutableListOf<String>()
        val winners = mutableListOf<SyncChange>()
        for (change in request.changes) {
            when {
                change.table !in SyncProtocol.TABLES -> rejected += "${change.table}/${change.id}"
                change.updatedAt > now + SyncProtocol.MAX_FUTURE_SKEW_MS -> rejected += "${change.table}/${change.id}"
                else -> store.applyIfNewer(change)?.let { winners += it }
            }
        }

        before?.let { announce(it, request.deviceId) }
        val page = store.changesSince(request.since, SyncProtocol.PAGE_SIZE + 1)
        val returned = page.take(SyncProtocol.PAGE_SIZE)
        val returnedKeys = returned.map { it.change.table to it.change.id }.toSet()
        return SyncResponse(
            cursor = returned.lastOrNull()?.seq ?: request.since,
            // The winners may be older than the cursor; send them anyway so the client corrects itself.
            changes = returned.map { it.change } + winners.filter { (it.table to it.id) !in returnedKeys },
            hasMore = page.size > SyncProtocol.PAGE_SIZE,
            rejected = rejected,
        )
    }

    /**
     * The server's own edits (the web dashboard), under the same rules and lock as a phone's
     * push. Returns the rows that lost to something newer already stored.
     */
    @Synchronized
    fun write(changes: List<SyncChange>, source: String = "server"): List<SyncChange> {
        val before = store.latestSeq()
        val losers = changes.mapNotNull { change ->
            require(change.table in SyncProtocol.TABLES) { "unknown table ${change.table}" }
            store.applyIfNewer(change)
        }
        announce(before, source)
        return losers
    }

    private fun announce(before: Long, source: String) {
        val after = store.latestSeq()
        if (after > before) onChange(SyncEvent(after, source))
    }

    fun current(table: String): List<SyncChange> = store.current(table)
}

/** A [SyncStore] in a map, for tests (and as the reference behaviour for the Postgres one). */
class InMemorySyncStore : SyncStore {
    private val rows = linkedMapOf<Pair<String, String>, Sequenced>()
    private var seq = 0L

    @Synchronized
    override fun applyIfNewer(change: SyncChange): SyncChange? {
        val key = change.table to change.id
        val stored = rows[key]
        if (stored != null && stored.change.updatedAt >= change.updatedAt) {
            return stored.change.takeIf { it.updatedAt > change.updatedAt }
        }
        rows[key] = Sequenced(++seq, change)
        return null
    }

    @Synchronized
    override fun changesSince(since: Long, limit: Int): List<Sequenced> =
        rows.values.filter { it.seq > since }.sortedBy { it.seq }.take(limit)

    @Synchronized
    override fun current(table: String): List<SyncChange> =
        rows.values.map { it.change }.filter { it.table == table }

    @Synchronized
    override fun latestSeq(): Long = seq
}
