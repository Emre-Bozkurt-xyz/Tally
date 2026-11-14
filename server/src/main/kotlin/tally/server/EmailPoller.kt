package tally.server

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import tally.core.email.HtmlText
import tally.core.email.MailMessage
import tally.core.email.ReceiptFilter
import tally.server.web.EmailReceipts
import tally.server.web.OrderStore
import tally.server.web.StoredOrder
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Properties
import javax.sql.DataSource

/** Where reading left off: IMAP UIDs only mean something within one UIDVALIDITY. */
data class MailCursor(val uidValidity: Long, val lastUid: Long)

/**
 * A mailbox read from where it left off. [each] gets every message newer than [cursor] (or
 * since [since] the first time), oldest first, and returns false to stop before that message.
 * Returns where to pick up next time: after the last message [each] accepted.
 */
interface Mailbox {
    fun read(cursor: MailCursor?, since: Instant, each: (MailMessage) -> Boolean): MailCursor
}

/** Gmail over IMAP with an app password, read-only (EXAMINE): nothing is marked read or moved. */
class ImapMailbox(
    private val address: String,
    private val appPassword: String,
    private val folderName: String = "INBOX",
    private val host: String = "imap.gmail.com",
) : Mailbox {
    override fun read(cursor: MailCursor?, since: Instant, each: (MailMessage) -> Boolean): MailCursor {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(host, 993, address, appPassword)
        try {
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val uids = folder as UIDFolder
                val validity = uids.uidValidity
                val messages: List<Message> = if (cursor == null || cursor.uidValidity != validity) {
                    folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date.from(since))).toList()
                } else {
                    // Asking for "after the last one" returns the last one when there's nothing new.
                    uids.getMessagesByUID(cursor.lastUid + 1, UIDFolder.MAXUID).filter { uids.getUID(it) > cursor.lastUid }
                }
                var last = cursor?.takeIf { it.uidValidity == validity }?.lastUid ?: 0L
                for (message in messages.sortedBy { uids.getUID(it) }) {
                    val uid = uids.getUID(message)
                    val handled = each(
                        MailMessage(
                            id = message.getHeader("Message-ID")?.firstOrNull() ?: "uid:$validity:$uid",
                            from = message.from?.firstOrNull()?.let { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }.orEmpty(),
                            subject = message.subject.orEmpty(),
                            receivedAt = (message.receivedDate ?: message.sentDate)?.time ?: System.currentTimeMillis(),
                            text = { textOf(message) },
                        ),
                    )
                    if (!handled) break
                    last = maxOf(last, uid)
                }
                return MailCursor(validity, last)
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    private fun textOf(part: Part): String = when {
        part.isMimeType("text/plain") -> part.content as? String ?: ""
        part.isMimeType("text/html") -> HtmlText.plain(part.content as? String ?: "")
        part.isMimeType("multipart/alternative") -> {
            val parts = (part.content as Multipart).let { mp -> (0 until mp.count).map { mp.getBodyPart(it) } }
            // Receipts' HTML has the line items; plain alternatives are often a stub.
            (parts.firstOrNull { it.isMimeType("text/html") } ?: parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.lastOrNull())
                ?.let { textOf(it) }.orEmpty()
        }
        part.isMimeType("multipart/*") -> {
            val mp = part.content as Multipart
            (0 until mp.count).map { mp.getBodyPart(it) }
                .filter { Part.ATTACHMENT != it.disposition }
                .joinToString("\n") { textOf(it) }
        }
        part.isMimeType("message/rfc822") -> (part.content as? Part)?.let { textOf(it) }.orEmpty()
        else -> ""
    }
}

/** Where the poller left off, per mailbox. */
interface MailState {
    fun cursor(): MailCursor?
    fun save(cursor: MailCursor)
}

class InMemoryMailState : MailState {
    @Volatile private var current: MailCursor? = null
    override fun cursor() = current
    override fun save(cursor: MailCursor) {
        current = cursor
    }
}

/** [MailState] and [OrderStore] on Postgres (migration V4). */
class PostgresEmailStore(private val dataSource: DataSource, private val mailbox: String) : MailState, OrderStore {
    override fun cursor(): MailCursor? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT uid_validity, last_uid FROM email_state WHERE mailbox = ?").use { st ->
            st.setString(1, mailbox)
            st.executeQuery().use { rs -> if (rs.next()) MailCursor(rs.getLong(1), rs.getLong(2)) else null }
        }
    }

    override fun save(cursor: MailCursor) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO email_state (mailbox, uid_validity, last_uid) VALUES (?, ?, ?)
                ON CONFLICT (mailbox) DO UPDATE SET uid_validity = EXCLUDED.uid_validity, last_uid = EXCLUDED.last_uid, updated_at = now()
                """.trimIndent(),
            ).use { st ->
                st.setString(1, mailbox)
                st.setLong(2, cursor.uidValidity)
                st.setLong(3, cursor.lastUid)
                st.executeUpdate()
            }
        }
    }

    override fun get(orderKey: String): StoredOrder? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT transaction_id, total_cents FROM email_orders WHERE order_key = ?").use { st ->
            st.setString(1, orderKey)
            st.executeQuery().use { rs -> if (rs.next()) StoredOrder(rs.getString(1), rs.getLong(2)) else null }
        }
    }

    override fun put(orderKey: String, order: StoredOrder) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO email_orders (order_key, transaction_id, total_cents) VALUES (?, ?, ?)
                ON CONFLICT (order_key) DO UPDATE SET transaction_id = EXCLUDED.transaction_id, total_cents = EXCLUDED.total_cents, updated_at = now()
                """.trimIndent(),
            ).use { st ->
                st.setString(1, orderKey)
                st.setString(2, order.transactionId)
                st.setLong(3, order.totalCents)
                st.executeUpdate()
            }
        }
    }
}

/**
 * Checks the mailbox every few minutes and hands new mail to [receipts]. The first run looks
 * back [firstLookBack]; later runs pick up after the last message seen. At most [dailyLimit]
 * receipts a day are read by the model; past that, the rest of the day's candidates wait for
 * the next day (the cursor stops before them).
 */
class EmailPoller(
    private val mailbox: Mailbox,
    private val state: MailState,
    private val receipts: EmailReceipts,
    private val clock: Clock,
    private val dailyLimit: Int = 100,
    private val firstLookBack: Duration = Duration.ofDays(7),
) {
    private var day: LocalDate? = null
    private var readToday = 0

    /** One pass. Returns what happened to each message, for logs and tests. */
    fun runOnce(): List<EmailReceipts.Outcome> {
        val outcomes = mutableListOf<EmailReceipts.Outcome>()
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        if (day != today) {
            day = today
            readToday = 0
        }
        val end = mailbox.read(state.cursor(), clock.instant().minus(firstLookBack)) { message ->
            if (ReceiptFilter.isCandidate(message.subject)) {
                if (readToday >= dailyLimit) {
                    log.warn("Email: daily limit of $dailyLimit receipts reached; the rest wait until tomorrow")
                    return@read false
                }
                readToday++
            }
            try {
                outcomes += receipts.handle(message)
                true
            } catch (e: IOException) {
                // The model or the network is down: try this message again next time.
                log.warn("Email: couldn't read a receipt (${e.message?.take(200)}); trying again later")
                false
            } catch (e: Exception) {
                // Something about this one message: skip it rather than stall on it forever.
                log.warn("Email: skipped a message: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                true
            }
        }
        state.save(end)
        return outcomes
    }

    fun start(scope: CoroutineScope, every: Duration) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val outcomes = runOnce()
                    val added = outcomes.count { it is EmailReceipts.Outcome.Added }
                    val matched = outcomes.count { it is EmailReceipts.Outcome.Matched || it is EmailReceipts.Outcome.Updated }
                    if (added + matched > 0) log.info("Email: $added receipts added, $matched matched or updated")
                } catch (e: Exception) {
                    // Never the password or the mail: just what went wrong.
                    log.warn("Email check failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                }
                delay(every.toMillis())
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(EmailPoller::class.java)
    }
}
