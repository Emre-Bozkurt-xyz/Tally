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
}
