package tally.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun `frames end at a blank line, comments and other fields are skipped`() {
        val parser = SseParser()
        val lines = listOf(
            "retry: 5000", ": connected", "",
            ": ping", "",
            "event: sync", "data: {\"cursor\":7,\"source\":\"web-abc\"}", "",
            "data: a", "data: b", "",
        )
        val frames = lines.mapNotNull { parser.feed(it) }
        assertEquals(
            listOf(SseParser.Frame("sync", "{\"cursor\":7,\"source\":\"web-abc\"}"), SseParser.Frame("message", "a\nb")),
            frames,
        )
        assertNull(SseParser().feed(""))
    }
}
