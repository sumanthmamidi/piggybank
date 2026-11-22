package app.piggybank.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.piggybank.db.PiggyDatabase

/** JVM driver, used for tests. Defaults to an in-memory database. */
actual class DriverFactory(private val url: String = JdbcSqliteDriver.IN_MEMORY) {
    actual fun create(): SqlDriver =
        JdbcSqliteDriver(url).also { PiggyDatabase.Schema.create(it) }
}
