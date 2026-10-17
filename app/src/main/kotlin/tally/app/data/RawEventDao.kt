package tally.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {
    @Insert
    suspend fun insert(event: RawEventEntity)

    @Query("SELECT * FROM raw_events ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<RawEventEntity>>

    @Query("SELECT * FROM raw_events WHERE id = :id")
    fun observeById(id: String): Flow<RawEventEntity?>

    @Query("SELECT * FROM raw_events ORDER BY receivedAt ASC")
    suspend fun getAll(): List<RawEventEntity>

    /** Bank and wallet notifications no parser understood: inbox flags. */
    @Query("SELECT * FROM raw_events WHERE parseStatus = 'FAILED' AND source IN (:sources) ORDER BY postedAt DESC")
    fun observeFailed(sources: Set<String>): Flow<List<RawEventEntity>>

    @Query("UPDATE raw_events SET parseStatus = :status, transactionId = :transactionId WHERE id = :id")
    suspend fun markParsed(id: String, status: String, transactionId: String?)

    @Query("SELECT * FROM raw_events WHERE transactionId = :transactionId")
    suspend fun linkedTo(transactionId: String): List<RawEventEntity>

    /** Watched-package events not yet turned into a transaction, oldest first (for re-parse). */
    @Query(
        """
        SELECT * FROM raw_events
        WHERE transactionId IS NULL AND source IN (:sources) AND parseStatus != 'IGNORED'
        ORDER BY postedAt ASC
        """,
    )
    suspend fun unlinked(sources: Set<String>): List<RawEventEntity>

    /** `IS` instead of `=` so null title/text/bigText compare equal. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM raw_events
            WHERE sbnKey = :sbnKey AND receivedAt >= :since
              AND title IS :title AND text IS :text AND bigText IS :bigText
        )
        """,
    )
    suspend fun existsRecentDuplicate(
        sbnKey: String,
        since: Long,
        title: String?,
        text: String?,
        bigText: String?,
    ): Boolean

    /** Returns how many were deleted. */
    @Query("DELETE FROM raw_events WHERE source NOT IN (:keep)")
    suspend fun deleteSourcesNotIn(keep: Set<String>): Int

    @Query(
        """
        SELECT source, COUNT(*) AS eventCount, MAX(receivedAt) AS lastReceivedAt
        FROM raw_events GROUP BY source
        """,
    )
    fun observeSourceStats(): Flow<List<SourceStats>>
}
