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
}

data class Sequenced(val seq: Long, val change: SyncChange)

/**
 * The sync rules, shared by the real server and tests: last write wins per row, the server
 * hands back everything after the client's cursor, and rows from a clock too far in the
 * future are refused. Requests are handled one at a time so sequence numbers commit in order.
 */
class SyncServer(private val store: SyncStore, private val clock: Clock = Clock.systemUTC()) {
    @Synchronized
    fun handle(request: SyncRequest): SyncResponse {
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
}
