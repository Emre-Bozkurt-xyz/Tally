package tally.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One row of one table, as it travels between the phone and the server. The server stores
 * [data] as-is (JSON), so a new column on the phone needs no server migration.
 */
@Serializable
data class SyncChange(
    val table: String,
    val id: String,
    /** Epoch ms of the last write. The later write wins. */
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JsonObject,
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    /** The server cursor from the previous response (0 on first sync). */
    val since: Long,
    val changes: List<SyncChange>,
)

@Serializable
data class SyncResponse(
    /** Pass as `since` next time. */
    val cursor: Long,
    /** Rows written on the server after `since`, plus the server's version of any row you sent an older copy of. */
    val changes: List<SyncChange>,
    /** More rows wait after [cursor]: sync again right away. */
    val hasMore: Boolean,
    /** "table/id" of rows refused: unknown table, or `updatedAt` too far in the future. */
    val rejected: List<String> = emptyList(),
)

object SyncProtocol {
    /** Tables that sync. Names are the phone's Room table names. */
    val TABLES = setOf(
        "transactions",
        "splits",
        "tags",
        "transaction_tags",
        "categories",
        "accounts",
        "account_identifiers",
        "merchants",
        "aliases",
        "rules",
        "raw_events",
    )

    /** A phone clock this far ahead would win every conflict for days; refuse such rows. */
    const val MAX_FUTURE_SKEW_MS = 5 * 60_000L

    /** Rows per response page. */
    const val PAGE_SIZE = 500

    /** Rows per request when pushing. */
    const val PUSH_BATCH = 200
}
