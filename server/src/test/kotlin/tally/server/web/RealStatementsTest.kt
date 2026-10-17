package tally.server.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tally.core.statement.RbcStatements
import java.io.File

/**
 * Real RBC statements, which never go in the repo: set TALLY_STATEMENTS_DIR to a folder of
 * statement PDFs to check that each one reads and adds up to its own totals. Prints counts only.
 */
@EnabledIfEnvironmentVariable(named = "TALLY_STATEMENTS_DIR", matches = ".+")
class RealStatementsTest {
    @Test
    fun `every statement reads and adds up`() {
        val pdfs = File(System.getenv("TALLY_STATEMENTS_DIR")).listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.orEmpty()
        assertTrue(pdfs.isNotEmpty(), "no PDFs in TALLY_STATEMENTS_DIR")
        for (pdf in pdfs) {
            val statement = RbcStatements.parse(StatementImport.pdfText(pdf.readBytes()))
            assertNotNull(statement, "${pdf.name} isn't recognized")
            println("${pdf.name}: ${statement!!.kind}, ${statement.lines.size} rows, ${statement.lines.groupingBy { it.kind }.eachCount()}")
            assertEquals(emptyList<String>(), statement.problems, pdf.name)
            assertTrue(statement.lines.isNotEmpty(), pdf.name)
        }
    }
}
