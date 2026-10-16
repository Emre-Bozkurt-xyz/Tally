package tally.core.capture

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class RawEventExportTest {
    private val event = ExportedRawEvent(
        id = "0b6f3c9e-1f7a-4c1e-9a55-2f1f0e1c7d10",
        source = WatchedPackages.RBC,
        receivedAt = 1_760_000_000_500,
        postedAt = 1_760_000_000_000,
        zoneId = "America/Toronto",
        sbnKey = "0|com.rbc.mobile.android|1|null|10123",
        title = "Credit card purchase",
        text = "A purchase of $12.34 was made at TIM HORTONS",
        extras = buildJsonObject { put("android.title", JsonPrimitive("Credit card purchase")) },
    )

    @Test
    fun `round trips through JSON`() {
        val export = RawEventExport(exportedAt = 1_760_000_100_000, events = listOf(event))
        assertEquals(export, RawEventExportCodec.decode(RawEventExportCodec.encode(export)))
    }

    @Test
    fun `ignores unknown keys from newer app versions`() {
        val text = """
            {"version": 1, "exportedAt": 1, "futureField": true, "events": [
              {"id": "a", "source": "com.example", "receivedAt": 2, "postedAt": 1,
               "zoneId": "UTC", "somethingNew": 42}
            ]}
        """.trimIndent()
        val decoded = RawEventExportCodec.decode(text)
        assertEquals("com.example", decoded.events.single().source)
    }

    @Test
    fun `converts to RawNotification using post time`() {
        val n = event.toRawNotification()
        assertEquals(WatchedPackages.RBC, n.packageName)
        assertEquals(Instant.ofEpochMilli(1_760_000_000_000), n.postedAt)
        assertEquals(event.text, n.text)
    }
}
