package app.piggybank.backup

import app.piggybank.db.PiggyDatabase

/** The platform-neutral parts of backup: what goes in the manifest, the log, and the settings. */
class BackupService(private val db: PiggyDatabase) {

    fun counts(): Map<String, Long> = db.appQueries.countRows().executeAsOne().let {
        mapOf("txns" to it.txns, "sms" to it.sms, "templates" to it.templates, "rules" to it.rules,
            "categories" to it.categories, "budgets" to it.budgets, "incomes" to it.incomes)
    }

    /** Nightly backup is on unless the user turned it off. */
    var nightly: Boolean
        get() = db.appQueries.getSetting(NIGHTLY).executeAsOneOrNull() != "0"
        set(on) = db.appQueries.putSetting(NIGHTLY, if (on) "1" else "0")

    /** Google Drive backup: the account it uses, or null when it's off. */
    var driveAccount: String?
        get() = db.appQueries.getSetting(DRIVE).executeAsOneOrNull()?.takeIf { it.isNotEmpty() }
        set(email) = db.appQueries.putSetting(DRIVE, email ?: "")

    fun log(at: Long, fileName: String, location: String, uri: String?, size: Long?, sha256: String?, schema: Long, error: String?) =
        db.appQueries.insertBackupLog(at, fileName, location, uri, size, sha256, schema, if (error == null) 1 else 0, error)

    data class Status(val lastOkAt: Long?, val lastOkFile: String?, val lastOkSize: Long?, val lastError: String?, val failingNights: Int)

    fun status(location: String = "LOCAL"): Status {
        val recent = db.appQueries.selectRecentBackups(40).executeAsList().filter { it.location == location }
        val ok = recent.firstOrNull { it.ok == 1L }
        return Status(
            lastOkAt = ok?.created_at, lastOkFile = ok?.file_name, lastOkSize = ok?.size_bytes,
            lastError = recent.firstOrNull()?.takeIf { it.ok == 0L }?.error,
            failingNights = recent.takeWhile { it.ok == 0L }.size,
        )
    }

    companion object {
        const val NIGHTLY = "backup_nightly"
        const val DRIVE = "backup_drive_account"
    }
}
