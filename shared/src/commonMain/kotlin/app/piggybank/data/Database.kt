package app.piggybank.data

import app.cash.sqldelight.db.SqlDriver
import app.piggybank.db.PiggyDatabase

/** Each platform supplies its SQLite driver. The database file lives in app-private storage. */
expect class DriverFactory {
    fun create(): SqlDriver
}

const val DATABASE_NAME = "piggybank.db"

fun createDatabase(driver: SqlDriver): PiggyDatabase {
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return PiggyDatabase(driver)
}
