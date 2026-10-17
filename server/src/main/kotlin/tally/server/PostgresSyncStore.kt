package tally.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tally.core.sync.Sequenced
import tally.core.sync.SyncChange
import tally.core.sync.SyncStore
import java.sql.ResultSet
import javax.sql.DataSource

/** [SyncStore] on the `sync_rows` table (migration V2). */
class PostgresSyncStore(private val dataSource: DataSource) : SyncStore {
    override fun applyIfNewer(change: SyncChange): SyncChange? = dataSource.connection.use { connection ->
        // Insert, or overwrite only when the incoming row is newer. RETURNING is empty when it wasn't.
        val applied = connection.prepareStatement(
            """
            INSERT INTO sync_rows (table_name, id, updated_at, deleted, data)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (table_name, id) DO UPDATE SET
                updated_at = EXCLUDED.updated_at,
                deleted = EXCLUDED.deleted,
                data = EXCLUDED.data,
                server_seq = nextval('sync_seq'),
                received_at = now()
            WHERE sync_rows.updated_at < EXCLUDED.updated_at
            RETURNING server_seq
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, change.table)
            statement.setString(2, change.id)
            statement.setLong(3, change.updatedAt)
            statement.setBoolean(4, change.deleted)
            statement.setString(5, change.data.toString())
            statement.executeQuery().use { it.next() }
        }
        if (applied) return@use null

        connection.prepareStatement(
            "SELECT table_name, id, updated_at, deleted, data FROM sync_rows WHERE table_name = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, change.table)
            statement.setString(2, change.id)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                rs.toChange().takeIf { it.updatedAt > change.updatedAt }
            }
        }
    }

    override fun changesSince(since: Long, limit: Int): List<Sequenced> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT table_name, id, updated_at, deleted, data, server_seq FROM sync_rows
            WHERE server_seq > ? ORDER BY server_seq LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, since)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Sequenced(rs.getLong("server_seq"), rs.toChange())) }
            }
        }
    }

    private fun ResultSet.toChange() = SyncChange(
        table = getString("table_name"),
        id = getString("id"),
        updatedAt = getLong("updated_at"),
        deleted = getBoolean("deleted"),
        data = Json.parseToJsonElement(getString("data")).jsonObject,
    )
}

