package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.core.capture.WatchedPackages
import tally.core.model.CategorySource
import tally.core.model.ParseStatus
import tally.core.model.TransactionStatus
import tally.core.rules.TomlImport
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RulesRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T02:00:00Z"), ZoneId.of("America/Toronto"))
    private val repo = RulesRepository(db, clock)
    private val pipeline = CapturePipeline(db, CaptureMemory(db, clock), clock)

    private val groceries = DefaultData.categoryId("Food/Groceries")

    @Before
    fun seed() = runTest { DefaultData.seedIfEmpty(db) }

    @After
    fun close() = db.close()

    private suspend fun captureFoodieFruitie(at: Instant = Instant.ofEpochMilli(1_792_199_089_433)): CapturePipeline.Outcome.Captured {
        val event = RawEventEntity(
            id = UUID.randomUUID().toString(), source = WatchedPackages.GOOGLE_WALLET,
            receivedAt = at.toEpochMilli(), postedAt = at.toEpochMilli(), zoneId = "America/Toronto",
            sbnKey = null, channelId = null, isGroupSummary = false, title = "FOODIE FRUITIE",
            text = "CA\$34.32 with Royal Bank Debit/Débit ••3881", bigText = null, extrasJson = "{}",
            parseStatus = ParseStatus.PENDING.name, transactionId = null,
        )
        db.rawEventDao().insert(event)
        return pipeline.process(event) as CapturePipeline.Outcome.Captured
    }

    private suspend fun importOk(text: String) {
        val result = repo.importToml(text)
        assertTrue(result.toString(), result is TomlImport.Ok)
    }

    @Test
    fun `export then import changes nothing and keeps ids`() = runTest {
        val before = db.categoryDao().getActive().sortedBy { it.id }
        val text = repo.exportToml()
        importOk(text)
        assertEquals(before.map { it.id to it.name }, db.categoryDao().getActive().sortedBy { it.id }.map { it.id to it.name })
        assertEquals(text, repo.exportToml())
    }

    @Test
    fun `an imported rule categorizes and confirms captures, with tags`() = runTest {
        importOk(
            repo.exportToml() + """

                [[rule]]
                merchant = "Foodie Fruitie"
                category = "Food/Groceries"
                tags = ["market"]
            """.trimIndent(),
        )
        val captured = captureFoodieFruitie().transaction
        assertEquals(groceries, captured.categoryId)
        assertEquals(CategorySource.RULE.name, captured.categorySource)
        assertEquals(TransactionStatus.CONFIRMED.name, captured.status)
        assertTrue(repo.exportToml().contains("tags = [\"market\"]"))
        assertEquals("market", db.tagDao().findByName("market")?.name)
    }

    @Test
    fun `aliases rename merchants on capture`() = runTest {
        importOk(
            repo.exportToml() + """

                [[alias]]
                match = '(?i)^foodie'
                name = "Foodie Fruitie Market"
            """.trimIndent(),
        )
        assertEquals("Foodie Fruitie Market", captureFoodieFruitie().merchantName)
    }

    @Test
    fun `always do this adds a first rule and can fix the past`() = runTest {
        val first = captureFoodieFruitie()
        val merchantId = first.transaction.merchantId!!

        assertEquals(1, repo.alwaysCategorize(merchantId, "Foodie Fruitie", groceries, maxAmountCents = null, applyToPast = true))
        assertEquals(groceries, db.transactionDao().getById(first.transaction.id)?.categoryId)

        val next = captureFoodieFruitie(Instant.parse("2026-10-20T15:00:00Z")).transaction
        assertEquals(CategorySource.RULE.name, next.categorySource)
        assertEquals(TransactionStatus.CONFIRMED.name, next.status)
    }

    @Test
    fun `categories missing from the file are deleted, others keep their ids`() = runTest {
        importOk(
            """
            [categories]
            Food = ["Groceries", "Coffee"]
            Fun = []
            """.trimIndent(),
        )
        val active = db.categoryDao().getActive()
        assertEquals(setOf("Food", "Groceries", "Coffee", "Fun"), active.map { it.name }.toSet())
        assertEquals(groceries, active.single { it.name == "Groceries" }.id)
        assertFalse(active.any { it.name == "Transport" })
    }

    @Test
    fun `saving rules validates, appends, and moves`() = runTest {
        fun rule(pattern: String, min: Long? = null, max: Long? = null) = RuleEntity(
            id = UUID.randomUUID().toString(), priority = 0, merchantPattern = pattern, minAmountCents = min,
            maxAmountCents = max, accountId = null, categoryId = groceries, tagsCsv = null, updatedAt = 0,
        )
        assertTrue(repo.saveRule(rule("Tim (Hortons")) != null)
        assertTrue(repo.saveRule(rule("Metro", min = 500, max = 100)) != null)

        val a = rule("Metro")
        val b = rule("Costco")
        assertEquals(null, repo.saveRule(a))
        assertEquals(null, repo.saveRule(b))
        assertEquals(listOf("Metro", "Costco"), db.ruleDao().getActive().map { it.merchantPattern })

        repo.moveRule(b.id, -1)
        assertEquals(listOf("Costco", "Metro"), db.ruleDao().getActive().map { it.merchantPattern })
        repo.deleteRule(a.id)
        assertEquals(listOf("Costco"), db.ruleDao().getActive().map { it.merchantPattern })
    }

    @Test
    fun `saving an alias renames past purchases`() = runTest {
        val before = captureFoodieFruitie().transaction
        assertEquals("Foodie Fruitie", db.merchantDao().getById(before.merchantId!!)?.name)

        assertEquals(null, repo.saveAlias(null, "(?i)^foodie", "Foodie Fruitie Market"))
        val after = db.transactionDao().getById(before.id)!!
        assertEquals("Foodie Fruitie Market", db.merchantDao().getById(after.merchantId!!)?.name)
        assertTrue(repo.saveAlias(null, "(?i)^foodie", "Again") != null) // Duplicate pattern.
        assertEquals(listOf("FOODIE FRUITIE"), repo.recentMerchantRaws())
    }

    @Test
    fun `an invalid file changes nothing`() = runTest {
        val before = repo.exportToml()
        val result = repo.importToml("[categories]\nFood = []\n\n[[rule]]\nmerchant = \"x\"\ncategory = \"Nope\"")
        assertTrue(result is TomlImport.Invalid)
        assertEquals(before, repo.exportToml())
    }

    @Test
    fun `ask rules round trip and only suggest`() = runTest {
        importOk(repo.exportToml() + "\n[[rule]]\nmerchant = 'Foodie Fruitie'\ncategory = \"Food/Groceries\"\nask = true\n")
        assertTrue(db.ruleDao().getActive().single().ask)
        assertTrue(repo.exportToml().contains("ask = true"))
        val captured = captureFoodieFruitie()
        assertEquals(groceries, captured.transaction.categoryId)
        assertEquals(TransactionStatus.CAPTURED.name, captured.transaction.status)
    }
}
