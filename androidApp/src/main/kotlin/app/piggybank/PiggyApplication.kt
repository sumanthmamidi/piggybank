package app.piggybank

import android.app.Application
import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase

import app.piggybank.backup.BackupWorker
import app.piggybank.backup.Backups

class PiggyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A restore the user confirmed is swapped in before anything opens the database (J1, J2).
        Backups.applyStagedRestore(this)
        BackupWorker.schedule(this)
    }

    /** One database for the whole app, opened lazily on first use. */
    val database: PiggyDatabase by lazy { createDatabase(DriverFactory(this).create()) }
}
