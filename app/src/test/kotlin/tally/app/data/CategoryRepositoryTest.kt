package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val now = Instant.parse("2026-10-16T13:30:00Z")
    private val repo = CategoryRepository(db, Clock.fixed(now, ZoneId.of("America/Toronto")))

    private val food = DefaultData.categoryId("Food")
    private val coffee = DefaultData.categoryId("Food/Coffee")
    private val transport = DefaultData.categoryId("Transport")
    private val health = DefaultData.categoryId("Health")

    @Before
    fun seed() = runTest { DefaultData.seedIfEmpty(db) }

    @After
    fun close() = db.close()

    private suspend fun active() = db.categoryDao().getActive()
    private suspend fun byName(name: String) = active().single { it.name == name }
    private suspend fun childNames(parentId: String) =
        active().filter { it.parentId == parentId }.sortedBy { it.sortOrder }.map { it.name }

    @Test
    fun `adds a subcategory at the end of its siblings`() = runTest {
        assertNull(repo.add("  Bakery ", food))
        val bakery = byName("Bakery")
        assertEquals(food, bakery.parentId)
        assertEquals("Bakery", childNames(food).last())
        assertEquals(now.toEpochMilli(), bakery.updatedAt)
    }

    @Test
    fun `rejects blank, slashes, duplicates among siblings, and a third level`() = runTest {
        assertNotNull(repo.add(" ", null))
        assertNotNull(repo.add("Food/Snacks", null))
        assertNotNull(repo.add("coffee", food))
        assertNotNull(repo.add("Espresso", coffee))
        // Same name under a different parent is fine.
        assertNull(repo.add("Coffee", transport))
    }

    @Test
    fun `moving to another parent puts it last there`() = runTest {
        assertNull(repo.update(coffee, "Coffee", transport))
        assertEquals(listOf("Transit", "Rideshare", "Coffee"), childNames(transport))
        assertFalse("Coffee" in childNames(food))
    }

    @Test
    fun `a parent with subcategories can't move under another`() = runTest {
        assertNotNull(repo.update(food, "Food", transport))
        assertNull(repo.update(health, "Health", transport))
    }

    @Test
    fun `move swaps with a neighbour and stops at the ends`() = runTest {
        assertNull(repo.move(coffee, -1))
        assertEquals(listOf("Groceries", "Dining out", "Coffee", "Delivery", "Convenience"), childNames(food))
        val first = DefaultData.categoryId("Food/Groceries")
        assertNull(repo.move(first, -1))
        assertEquals("Groceries", childNames(food).first())
    }

    @Test
    fun `pinning toggles`() = runTest {
        assertTrue(byName("Coffee").pinned)
        repo.setPinned(coffee, false)
        assertFalse(byName("Coffee").pinned)
    }

    @Test
    fun `delete is soft and refused while subcategories remain`() = runTest {
        assertNotNull(repo.delete(food))
        assertNull(repo.delete(coffee))
        assertFalse(active().any { it.id == coffee })
        assertTrue(db.categoryDao().observeAll().first().single { it.id == coffee }.deleted)
    }
}
