package tally.core.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Every `fixtures/<name>.json` (a raw event export) must have a `fixtures/<name>.expected.json`
 * listing what the parsers produce for each watched-package event. Adding a case is dropping
 * those two files in the folder.
 */
class GoldenFixturesTest {
    @Serializable
    private data class Expected(val note: String? = null, val results: List<ExpectedResult>)

    @Serializable
    private data class ExpectedResult(
        val id: String,
        /** PARSED, IGNORED or FAILED. */
        val result: String,
        val amountCents: Long? = null,
        val merchantRaw: String? = null,
        val occurredAt: Long? = null,
        val source: String? = null,
        val kind: String? = null,
        val accountLast4: String? = null,
        val accountName: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val fixturesDir: File =
        File(requireNotNull(javaClass.getResource("/fixtures")) { "fixtures folder missing" }.toURI())

    @TestFactory
    fun `fixtures parse as expected`(): List<DynamicTest> {
        val exports = fixturesDir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expected.json") }
            .orEmpty().sortedBy { it.name }
        assertTrue(exports.isNotEmpty(), "no fixtures found in $fixturesDir")

        return exports.flatMap { exportFile ->
            val expectedFile = File(fixturesDir, exportFile.name.removeSuffix(".json") + ".expected.json")
            val export = RawEventExportCodec.decode(exportFile.readText())
            val expected = json.decodeFromString(Expected.serializer(), expectedFile.readText())
                .results.associateBy { it.id }

            val watched = export.events.filter { it.source in WatchedPackages.ALL }
            listOf(
                DynamicTest.dynamicTest("${exportFile.name}: every watched event has an expectation") {
                    assertEquals(watched.map { it.id }.toSet(), expected.keys)
                },
            ) + watched.map { event ->
                DynamicTest.dynamicTest("${exportFile.name}: ${event.id}") {
                    assertEquals(expected.getValue(event.id), actual(event))
                }
            }
        }
    }

    private fun actual(event: ExportedRawEvent): ExpectedResult {
        val result = CaptureParsers.parse(event.toRawNotification())
        return when (result) {
            is ParseResult.Parsed -> with(result.capture) {
                ExpectedResult(
                    id = event.id,
                    result = "PARSED",
                    amountCents = amountCents,
                    merchantRaw = merchantRaw,
                    occurredAt = occurredAt.toEpochMilli(),
                    source = source.name,
                    kind = kind.name,
                    accountLast4 = accountHint?.last4,
                    accountName = accountHint?.accountName,
                )
            }
            is ParseResult.Ignored -> ExpectedResult(event.id, "IGNORED")
            is ParseResult.Failed -> ExpectedResult(event.id, "FAILED")
            null -> error("${event.source} is watched but CaptureParsers returned null")
        }
    }
}
