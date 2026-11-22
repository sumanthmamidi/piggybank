package app.piggybank

import android.app.Application
import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase

class PiggyApplication : Application() {
    /** One database for the whole app, opened lazily on first use. */
    val database: PiggyDatabase by lazy { createDatabase(DriverFactory(this).create()) }
}
