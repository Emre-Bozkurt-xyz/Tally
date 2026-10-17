package tally.server

import tally.core.llm.CachedAnswer
import tally.core.llm.CategoryCache
import javax.sql.DataSource

/** [CategoryCache] on the `llm_categories` table (migration V3). */
class PostgresCategoryCache(private val dataSource: DataSource, private val model: String) : CategoryCache {
    override fun get(merchantKey: String): CachedAnswer? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT category, categories_hash FROM llm_categories WHERE merchant_key = ?").use { statement ->
            statement.setString(1, merchantKey)
            statement.executeQuery().use { rs ->
                if (rs.next()) CachedAnswer(rs.getString("category"), rs.getString("categories_hash")) else null
            }
        }
    }

    override fun put(merchantKey: String, answer: CachedAnswer) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO llm_categories (merchant_key, category, categories_hash, model)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (merchant_key) DO UPDATE SET
                    category = EXCLUDED.category,
                    categories_hash = EXCLUDED.categories_hash,
                    model = EXCLUDED.model,
                    answered_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, merchantKey)
                statement.setString(2, answer.category)
                statement.setString(3, answer.categoriesHash)
                statement.setString(4, model)
                statement.executeUpdate()
            }
        }
    }
}
