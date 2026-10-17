package tally.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Something that can say whether the database answers; the readiness check uses it. */
fun interface DatabaseCheck {
    fun isUp(): Boolean
}

/** The Postgres connection pool. Migrations (src/main/resources/db/migration) run at startup. */
class Database(config: ServerConfig) : DatabaseCheck, AutoCloseable {
    val dataSource: DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = 5
            poolName = "tally"
        },
    )

    /** Applies pending migrations. One server instance, so running them on boot is safe. */
    fun migrate() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    }

    override fun isUp(): Boolean = runCatching {
        dataSource.connection.use { it.createStatement().use { s -> s.execute("SELECT 1") } }
    }.getOrDefault(false)

    override fun close() = (dataSource as HikariDataSource).close()
}
