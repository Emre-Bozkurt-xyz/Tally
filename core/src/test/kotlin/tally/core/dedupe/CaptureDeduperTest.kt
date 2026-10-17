package tally.core.dedupe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tally.core.capture.AccountHint
import tally.core.capture.Capture
import tally.core.model.Kind
import tally.core.model.Source
import java.time.Duration
import java.time.Instant

class CaptureDeduperTest {
    private val deduper = CaptureDeduper()
    private val t0 = Instant.parse("2026-10-17T01:04:49Z")

    private fun capture(source: Source, at: Instant = t0, cents: Long = 3432) =
        Capture(cents, null, at, source, AccountHint(last4 = "0000"), Kind.EXPENSE)

    private fun candidate(id: String, source: Source, at: Instant = t0, cents: Long = 3432, accountId: String? = null) =
        DedupeCandidate(id, cents, at, setOf(source), accountId)

    @Test
    fun `wallet then rbc merge (the real fixture, 21 s apart)`() {
        val wallet = candidate("w", Source.GOOGLE_WALLET)
        val rbc = capture(Source.RBC_ALERT, at = t0.plusSeconds(21))
        assertEquals(wallet, deduper.findMatch(rbc, accountId = null, candidates = listOf(wallet)))
    }

    @Test
    fun `rbc then wallet merge`() {
        val rbc = candidate("r", Source.RBC_ALERT, accountId = "chequing")
        val wallet = capture(Source.GOOGLE_WALLET, at = t0.plusSeconds(30))
        assertEquals(rbc, deduper.findMatch(wallet, accountId = null, candidates = listOf(rbc)))
    }

    @Test
    fun `two identical purchases from the same source stay separate`() {
        val first = candidate("w1", Source.GOOGLE_WALLET)
        val second = capture(Source.GOOGLE_WALLET, at = t0.plus(Duration.ofMinutes(2)))
        assertNull(deduper.findMatch(second, accountId = null, candidates = listOf(first)))
    }

    @Test
    fun `different amount, too far apart, or conflicting account do not merge`() {
        val wallet = candidate("w", Source.GOOGLE_WALLET, accountId = "visa")
        assertNull(deduper.findMatch(capture(Source.RBC_ALERT, cents = 3433), null, listOf(wallet)))
        assertNull(deduper.findMatch(capture(Source.RBC_ALERT, at = t0.plus(Duration.ofMinutes(11))), null, listOf(wallet)))
        assertNull(deduper.findMatch(capture(Source.RBC_ALERT), accountId = "chequing", candidates = listOf(wallet)))
        assertEquals(wallet, deduper.findMatch(capture(Source.RBC_ALERT), accountId = "visa", candidates = listOf(wallet)))
    }

    @Test
    fun `closest in time wins`() {
        val far = candidate("far", Source.GOOGLE_WALLET, at = t0.minus(Duration.ofMinutes(8)))
        val near = candidate("near", Source.GOOGLE_WALLET, at = t0.minusSeconds(20))
        assertEquals(near, deduper.findMatch(capture(Source.RBC_ALERT), null, listOf(far, near)))
    }
}
