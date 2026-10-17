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
