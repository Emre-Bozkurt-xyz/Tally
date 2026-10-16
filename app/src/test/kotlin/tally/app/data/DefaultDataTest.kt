package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultDataTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun `seeds accounts and the category tree once`() = runTest {
        DefaultData.seedIfEmpty(db)
        DefaultData.seedIfEmpty(db)

        val accounts = db.accountDao().observeActive().first()
        assertEquals(setOf("RBC Visa", "RBC Chequing", "Cash"), accounts.map { it.name }.toSet())
        assertEquals(DefaultData.categories().size, db.categoryDao().observeActive().first().size)
    }

    @Test
    fun `subcategories point at their parent`() {
        val byId = DefaultData.categories().associateBy { it.id }
        val coffee = byId.getValue(DefaultData.categoryId("Food/Coffee"))
        assertEquals("Coffee", coffee.name)
        assertEquals("Food", byId.getValue(coffee.parentId!!).name)
        assertNull(byId.getValue(DefaultData.categoryId("Food")).parentId)
        // Two levels only.
        DefaultData.categories().filter { it.parentId != null }.forEach { child ->
            assertNull(byId.getValue(child.parentId!!).parentId)
        }
    }

    @Test
    fun `ids are stable and seeded rows lose to any real edit`() {
        // Fixed known value: changing the id scheme would duplicate rows after a reinstall + sync.
        assertEquals("2b3b0672-9338-33a7-9327-dddac19ab555", DefaultData.categoryId("Food/Coffee"))
        assertEquals(DefaultData.categoryId("Food/Coffee"), DefaultData.categories().single { it.name == "Coffee" }.id)
        assertTrue(DefaultData.categories().all { it.updatedAt == 0L })
        assertTrue(DefaultData.accounts().all { it.updatedAt == 0L })
        val ids = DefaultData.categories().map { it.id } + DefaultData.accounts().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `pins four food subcategories`() {
        val pinned = DefaultData.categories().filter { it.pinned }.map { it.name }.toSet()
        assertEquals(setOf("Coffee", "Convenience", "Dining out", "Groceries"), pinned)
    }
}
