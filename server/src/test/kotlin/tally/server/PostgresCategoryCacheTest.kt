package tally.server

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tally.core.llm.CachedAnswer

/** Real Postgres when TALLY_TEST_DATABASE_URL is set (see [PostgresSyncStoreTest]). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TALLY_TEST_DATABASE_URL", matches = ".+")
class PostgresCategoryCacheTest {
    private val database = Database(
        ServerConfig(
            port = 0,
            databaseUrl = System.getenv("TALLY_TEST_DATABASE_URL"),
            databaseUser = System.getenv("TALLY_TEST_DATABASE_USER") ?: "tally",
            databasePassword = System.getenv("TALLY_TEST_DATABASE_PASSWORD") ?: "test",
            access = null,
        ),
    ).also { it.migrate() }
    private val cache = PostgresCategoryCache(database.dataSource, model = "test-model")

    @AfterAll
    fun close() = database.close()

    @Test
    fun `answers, including don't know, round trip and overwrite`() {
        database.dataSource.connection.use { it.createStatement().use { s -> s.execute("TRUNCATE llm_categories") } }
        assertEquals(null, cache.get("foodie fruitie"))
        cache.put("foodie fruitie", CachedAnswer(null, "h1"))
        assertEquals(CachedAnswer(null, "h1"), cache.get("foodie fruitie"))
        cache.put("foodie fruitie", CachedAnswer("Food/Groceries", "h2"))
        assertEquals(CachedAnswer("Food/Groceries", "h2"), cache.get("foodie fruitie"))
    }
}
