package app.piggybank.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.piggybank.db.PiggyDatabase

actual class DriverFactory {
    actual fun create(): SqlDriver = NativeSqliteDriver(PiggyDatabase.Schema, DATABASE_NAME)
}
