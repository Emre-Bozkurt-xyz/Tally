package tally.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs each migration against the schema JSON committed in `app/schemas/`.
 *
 * Uses the driver-based helper: the SupportSQLite one rejects absolute database paths on
 * Windows (it compares the configured name against the full path).
 */
@RunWith(RobolectricTestRunner::class)
class TallyDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = context.getDatabasePath("migration-test.db")
    private val driver = AndroidSQLiteDriver()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = driver,
        databaseClass = TallyDatabase::class,
    )

    @Test
    fun `1 to 6 keeps raw events and creates the new tables`() = runTest {
        helper.createDatabase(1).use { connection ->
            connection.execSQL(
                """
                INSERT INTO raw_events (id, source, receivedAt, postedAt, zoneId, sbnKey, channelId,
                    isGroupSummary, title, text, bigText, extrasJson, parseStatus, transactionId)
                VALUES ('raw-1', 'com.rbc.mobile.android', 1000, 999, 'America/Toronto', 'key', NULL,
                    0, 'Purchase', 'A purchase of ${'$'}4.25', NULL, '{}', 'PENDING', NULL)
                """.trimIndent(),
            )
        }

        // Validates each migrated schema matches its JSON exactly (tables, columns, indices).
        helper.runMigrationsAndValidate(2).close()
        helper.runMigrationsAndValidate(3).close()
        helper.runMigrationsAndValidate(4).close()
        helper.runMigrationsAndValidate(5).close()
        helper.runMigrationsAndValidate(6).close()
        helper.runMigrationsAndValidate(7).close()
        helper.runMigrationsAndValidate(8).close()

        val db = Room.databaseBuilder(context, TallyDatabase::class.java, dbFile.absolutePath)
            .setDriver(driver)
            .build()
        try {
            val raw = db.rawEventDao().getAll().single()
            assertEquals("raw-1", raw.id)
            assertEquals("A purchase of \$4.25", raw.text)
            // Old rows count as "changed long ago", so the first sync still sends them.
            assertEquals(0L, raw.updatedAt)

            DefaultData.seedIfEmpty(db)
            assertEquals(DefaultData.categories().size, db.categoryDao().observeActive().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `7 to 8 takes rent and bills out of the everyday number and keeps the rest`() = runTest {
        helper.createDatabase(7).use { connection ->
            for ((id, name, parent) in listOf(
                Triple("food", "Food", null),
                Triple("coffee", "Coffee", "food"),
                Triple("bills", "Bills", null),
                Triple("phone", "Phone", "bills"),
                Triple("rent", "rent", null),
                Triple("old-rent", "Rent", null),
            )) {
                val deleted = if (id == "old-rent") 1 else 0
                connection.execSQL(
                    """
                    INSERT INTO categories (id, name, parentId, monthlyBudgetCents, sortOrder, pinned, updatedAt, deleted)
                    VALUES ('$id', '$name', ${parent?.let { "'$it'" } ?: "NULL"}, NULL, 0, 0, 5, $deleted)
                    """.trimIndent(),
                )
            }
            connection.execSQL(
                """
                INSERT INTO transactions (id, amountCents, currency, occurredAt, zoneId, accountId, merchantId,
                    merchantRaw, categoryId, note, source, status, categorySource, kind, updatedAt, deleted)
                VALUES ('t1', 75000, 'CAD', 1000, 'America/Toronto', NULL, NULL, NULL, 'rent', NULL,
                    'RBC_ALERT', 'CONFIRMED', 'MANUAL', 'EXPENSE', 5, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(8).close()

        val db = Room.databaseBuilder(context, TallyDatabase::class.java, dbFile.absolutePath)
            .setDriver(driver)
            .build()
        try {
            val categories = db.categoryDao().observeAll().first().associateBy { it.id }
            assertEquals(
                mapOf("food" to true, "coffee" to true, "bills" to false, "phone" to false, "rent" to false, "old-rent" to true),
                categories.mapValues { it.value.counted },
            )
            // Changed rows sync; untouched ones don't.
            assertTrue(categories.getValue("rent").updatedAt > 5)
            assertEquals(5L, categories.getValue("food").updatedAt)

            val transaction = db.transactionDao().observeActive().first().single()
            assertNull(transaction.counted)
            assertEquals(75000L, transaction.amountCents)
            assertTrue(db.syncDao().settlementsSince(0).isEmpty())
        } finally {
            db.close()
        }
    }
}
