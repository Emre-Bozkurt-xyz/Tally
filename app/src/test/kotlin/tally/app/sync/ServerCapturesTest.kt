package tally.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import tally.app.data.TransactionEntity
import tally.core.model.Source
import tally.core.model.TransactionStatus

class ServerCapturesTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun capture(id: String, source: Source, daysAgo: Int) = TransactionEntity(
        id = id, amountCents = 64_12, currency = "CAD", occurredAt = now - daysAgo * day, zoneId = "America/Toronto",
        accountId = null, merchantId = null, merchantRaw = null, categoryId = null, note = null, source = source.name,
        status = TransactionStatus.CAPTURED.name, categorySource = null, kind = "EXPENSE", updatedAt = 0,
    )

    @Test
    fun `new receipts and recent statement rows notify, the phone's own and old rows don't`() {
        val waiting = listOf(
            capture("email-new", Source.EMAIL, 0),
            capture("statement-recent", Source.CSV, 2),
            capture("statement-old", Source.CSV, 20),
            capture("tap", Source.SAMSUNG_WALLET, 0),
            capture("email-seen", Source.EMAIL, 0),
        )
        assertEquals(
            listOf("email-new", "statement-recent"),
            SyncManager.newServerCaptures(setOf("email-seen"), waiting, now).map { it.id },
        )
    }
}
