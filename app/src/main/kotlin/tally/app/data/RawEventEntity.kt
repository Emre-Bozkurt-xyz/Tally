package tally.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A notification stored verbatim before any parsing. Parsers can be rewritten and re-run
 * over these at any time.
 */
@Entity(
    tableName = "raw_events",
    indices = [Index("receivedAt"), Index("sbnKey"), Index("source")],
)
data class RawEventEntity(
    @PrimaryKey val id: String,
    /** Package name, or `csv:<file>` for statement rows. */
    val source: String,
    /** Epoch ms when Tally received it. */
    val receivedAt: Long,
    /** Epoch ms from `StatusBarNotification.postTime`. */
    val postedAt: Long,
    /** Device zone at capture time. */
    val zoneId: String,
    /** `StatusBarNotification.key`, for dropping re-posts of the same notification. */
    val sbnKey: String?,
    val channelId: String?,
    val isGroupSummary: Boolean,
    val title: String?,
    val text: String?,
    val bigText: String?,
    /** Full `Notification.extras` dump, for parsers that haven't been written yet. */
    val extrasJson: String,
    /** A [tally.core.model.ParseStatus] name. */
    val parseStatus: String,
    val transactionId: String?,
)

/** Per-package capture stats, for the listener health check on the setup screen. */
data class SourceStats(
    val source: String,
    val eventCount: Int,
    val lastReceivedAt: Long,
)
