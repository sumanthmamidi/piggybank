package app.piggybank.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.piggybank.db.PiggyDatabase

actual class DriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(PiggyDatabase.Schema, context, DATABASE_NAME)
}
