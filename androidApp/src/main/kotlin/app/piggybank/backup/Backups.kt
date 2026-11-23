package app.piggybank.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import app.piggybank.BuildConfig
import app.piggybank.data.DATABASE_NAME
import app.piggybank.db.PiggyDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Making a backup (I6, nightly) and finding one to restore (J2, J3). */
object Backups {
    private val schema get() = PiggyDatabase.Schema.version

    /** Writes today's `.pbk` (replacing an earlier one from today), applies retention and logs the run. */
    @Synchronized
    fun backUpNow(context: Context, db: PiggyDatabase): Result<String> {
        val now = System.currentTimeMillis()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val name = BackupFile.fileName(today)
        val svc = BackupService(db)
        return runCatching {
            val folder = BackupFolder.current(context) ?: error("Pick a backup folder first (Android 9 and older)")
            val bytes = BackupFile.encode(snapshot(context), schema, BuildConfig.VERSION_NAME, now, svc.counts())
            val uri = folder.write(name, bytes)
            prune(folder)
            svc.log(now, name, "LOCAL", uri.toString(), bytes.size.toLong(), null, schema, null)
            "${folder.label}/$name"
        }.onFailure { e ->
            svc.log(now, name, "LOCAL", null, null, null, schema, e.message ?: e::class.simpleName)
        }.also { local ->
            // I4: Drive is tried on its own; a failed upload never affects the local copy.
            if (svc.driveAccount != null) toDrive(context, db, name, now)
        }
    }

    private fun toDrive(context: Context, db: PiggyDatabase, name: String, now: Long) {
        val svc = BackupService(db)
        runCatching {
            val token = DriveClient.silentToken(context, svc.driveAccount) ?: error("Open PiggyBank and reconnect Google Drive")
            val bytes = BackupFile.encode(snapshot(context), schema, BuildConfig.VERSION_NAME, now, svc.counts())
            val drive = DriveClient(token)
            val id = drive.upload(name, bytes)
            val ours = drive.list().mapNotNull { f -> BackupFile.dateOf(f.name)?.let { it to f } }
            val keep = Retention.keep(ours.map { it.first })
            ours.filter { it.first !in keep }.forEach { runCatching { drive.delete(it.second.id) } }
            svc.log(now, name, "DRIVE", id, bytes.size.toLong(), null, schema, null)
        }.onFailure { e ->
            svc.log(now, name, "DRIVE", null, null, null, schema, e.message ?: e::class.simpleName)
        }
    }

    /** Drive restore (J1): the newest valid backup in the app's hidden Drive folder. */
    fun newestValidOnDrive(token: String): Result<Found> = runCatching {
        val drive = DriveClient(token)
        val files = drive.list().filter { BackupFile.dateOf(it.name) != null }.sortedByDescending { BackupFile.dateOf(it.name) }
        if (files.isEmpty()) error("No PiggyBank backups in this Google account's Drive")
        val skipped = mutableListOf<String>()
        for (f in files) {
            when (val d = runCatching { BackupFile.decode(drive.download(f.id), schema) }.getOrElse { Decoded.Bad(it.message ?: "Couldn't download it") }) {
                is Decoded.Ok -> return@runCatching found(d, skipped)
                is Decoded.Bad -> skipped += "${f.name}: ${d.reason}"
            }
        }
        error("No readable backup on Drive. " + skipped.joinToString("; "))
    }

    /** Ran successfully today already (I2: at most once a day). */
    fun doneToday(db: PiggyDatabase): Boolean {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val svc = BackupService(db)
        fun today(at: Long?) = at != null && kotlinx.datetime.Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date == today
        return today(svc.status().lastOkAt) && (svc.driveAccount == null || today(svc.status("DRIVE").lastOkAt))
    }

    /** A consistent copy of the live database, taken while the app may be writing. */
    private fun snapshot(context: Context): ByteArray {
        val live = context.getDatabasePath(DATABASE_NAME)
        val out = File(context.cacheDir, "snapshot.db").apply { delete() }
        try {
            SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READWRITE).use { conn ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    conn.execSQL("VACUUM INTO ?", arrayOf(out.path))
                } else {
                    conn.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                    live.copyTo(out, overwrite = true)
                }
            }
            return out.readBytes()
        } finally {
            out.delete()
        }
    }

    private fun prune(folder: BackupFolder) {
        val ours = folder.list().mapNotNull { e -> BackupFile.dateOf(e.name)?.let { it to e } }
        val keep = Retention.keep(ours.map { it.first })
        ours.filter { it.first !in keep }.forEach { folder.delete(it.second) }
    }

    // ---- Restore ----

    /** A backup that checked out, waiting for the user to confirm. */
    data class Found(val date: String, val createdAt: Long, val counts: Map<String, Long>, val skipped: List<String>, val db: ByteArray)

    /** J3: the newest backup in the folder that is valid; damaged ones are skipped and named. */
    fun newestValid(folder: BackupFolder): Result<Found> = runCatching {
        val files = folder.list().filter { it.name.endsWith(".pbk") }.sortedByDescending { BackupFile.dateOf(it.name) ?: LocalDate(1970, 1, 1) }
        if (files.isEmpty()) error("No PiggyBank backups in ${folder.label}")
        val skipped = mutableListOf<String>()
        for (f in files) {
            when (val d = runCatching { BackupFile.decode(folder.read(f), schema) }.getOrElse { Decoded.Bad(it.message ?: "Couldn't read it") }) {
                is Decoded.Ok -> return@runCatching found(d, skipped)
                is Decoded.Bad -> skipped += "${f.name}: ${d.reason}"
            }
        }
        error("No readable backup. " + skipped.joinToString("; "))
    }

    fun fromFile(context: Context, uri: Uri): Result<Found> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        when (val d = BackupFile.decode(bytes, schema)) {
            is Decoded.Ok -> found(d, emptyList())
            is Decoded.Bad -> error(d.reason)
        }
    }

    private fun found(d: Decoded.Ok, skipped: List<String>) = Found(
        date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(d.manifest.createdAt)),
        createdAt = d.manifest.createdAt, counts = d.manifest.counts, skipped = skipped, db = d.db,
    )

    /**
     * Stages the backup's database; the app then restarts and swaps it in before anything opens the
     * database (see [applyStagedRestore]). The staged copy is checked to be a readable SQLite file first.
     */
    fun stage(context: Context, found: Found) {
        val staged = stagedFile(context)
        staged.writeBytes(found.db)
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { conn ->
            conn.rawQuery("PRAGMA quick_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok") { "The backup's database is damaged" }
            }
        }
    }

    /** Called at app start: replaces the database with a staged backup, if there is one. */
    fun applyStagedRestore(context: Context) {
        val staged = stagedFile(context)
        if (!staged.exists()) return
        val live = context.getDatabasePath(DATABASE_NAME)
        live.parentFile?.mkdirs()
        listOf("", "-wal", "-shm", "-journal").forEach { File(live.path + it).delete() }
        staged.copyTo(live, overwrite = true)
        staged.delete()
    }

    private fun stagedFile(context: Context) = File(context.getDatabasePath(DATABASE_NAME).parentFile, "restore.pending")
}
