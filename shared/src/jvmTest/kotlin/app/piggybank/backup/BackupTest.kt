package app.piggybank.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import app.piggybank.review.Classifier
import app.piggybank.review.ReviewService
import app.piggybank.sms.IncomingSms
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M4 / TEST_CASES I and J: the backup file, retention and a full round trip. Synthetic data only. */
class BackupTest {
    private val tz = TimeZone.of("Asia/Kolkata")

    private fun fileDb(f: File): Pair<JdbcSqliteDriver, PiggyDatabase> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${f.path}", Properties())
        if (driver.executeQuery(null, "SELECT count(*) FROM sqlite_master WHERE name = 'txn'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)) }, 0).value == 0L) {
            PiggyDatabase.Schema.create(driver)
        }
        return driver to createDatabase(driver)
    }

    @Test fun manifestRoundTripsAndChecksumGuardsTheData() {
        val db = "pretend sqlite bytes".encodeToByteArray()
        val file = BackupFile.encode(db, 1, "0.1.0", 1_800_000_000_000, mapOf("txns" to 3L, "sms" to 9L))
        val ok = assertIs<Decoded.Ok>(BackupFile.decode(file, 1))
        assertEquals(mapOf("txns" to 3L, "sms" to 9L), ok.manifest.counts)
        assertTrue(ok.db.contentEquals(db))

        val flipped = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertIs<Decoded.Bad>(BackupFile.decode(flipped, 1)) // J3: damaged
        assertIs<Decoded.Bad>(BackupFile.decode(file.copyOf(file.size - 4), 1)) // cut short
        assertIs<Decoded.Bad>(BackupFile.decode("hello".encodeToByteArray(), 1)) // not a backup
        assertIs<Decoded.Bad>(BackupFile.decode(file, 0)) // from a newer app
    }

    @Test fun fileNamesCarryTheDate() {
        assertEquals("piggybank-2026-11-23.pbk", BackupFile.fileName(LocalDate(2026, 11, 23)))
        assertEquals(LocalDate(2026, 11, 23), BackupFile.dateOf("piggybank-2026-11-23.pbk"))
        assertNull(BackupFile.dateOf("piggybank-2026-11-23 (1).pbk"))
    }

    /** I3: 120 nightly files keep 7 daily, then 4 weekly, then 3 monthly. */
    @Test fun retentionKeeps7Daily4Weekly3Monthly() {
        val today = LocalDate(2026, 11, 23) // a Monday
        val all = (0 until 120).map { today.minus(DatePeriod(days = it)) }
        val keep = Retention.keep(all).sortedDescending()
        assertEquals((0 until 7).map { today.minus(DatePeriod(days = it)) }, keep.take(7))
        val older = keep.drop(7)
        assertEquals(7, older.size) // 4 weekly + 3 monthly
        assertTrue(older.take(4).zipWithNext().all { (a, b) -> a.toEpochDays() - b.toEpochDays() == 7 })
        assertEquals(3, older.drop(4).map { it.monthNumber }.distinct().size)
        assertEquals(keep.toSet(), Retention.keep(keep)) // pruning again changes nothing
    }

    /** J1/I7: back up, wipe, restore: everything is back and the app opens it normally. */
    @Test fun backupWipeRestoreRoundTrip() {
        val dir = createTempDirectory()
        val live = File(dir, "live.db")
        val (driver, db) = fileDb(live)
        SmsIngestor(db, tz).ingest(listOf(
            IncomingSms(1, "VM-HDFCBK", "Rs.420.00 spent on HDFC Bank Card x4421 at SWIGGY on 20-11-26.", 1_795_000_000_000, 1_795_000_000_000),
            IncomingSms(2, "VM-HDFCBK", "Rs.90.00 debited from A/c XX1111 to VPA tea.stall@okaxis on 21-11-26.", 1_795_100_000_000, 1_795_100_000_000),
        ))
        Classifier(db, now = { 1_795_200_000_000 }, tz = tz).run()
        val first = db.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList().first()
        ReviewService(db, tz) { 1_795_200_000_000 }.tag(listOf(first.id), "SPEND", first.category_id ?: 1, "WANT", remember = true)
        val counts = BackupService(db).counts()

        val snap = File(dir, "snap.db")
        driver.execute(null, "VACUUM INTO '${snap.path}'", 0)
        val pbk = BackupFile.encode(snap.readBytes(), PiggyDatabase.Schema.version, "test", 1_795_300_000_000, counts)
        driver.close()
        live.delete() // the phone is wiped

        val restored = assertIs<Decoded.Ok>(BackupFile.decode(pbk, PiggyDatabase.Schema.version))
        File(dir, "new.db").writeBytes(restored.db)
        val (_, back) = fileDb(File(dir, "new.db"))
        assertEquals(counts, BackupService(back).counts())
        assertEquals(1, back.ruleQueries.selectAll().executeAsList().size)
        assertEquals(1, back.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList().count { it.reviewed_at != null })

        // J6: SMS read again after the restore are not doubled.
        SmsIngestor(back, tz).ingest(listOf(
            IncomingSms(7, "VM-HDFCBK", "Rs.90.00 debited from A/c XX1111 to VPA tea.stall@okaxis on 21-11-26.", 1_795_100_000_000, 1_795_100_000_000),
        ))
        assertEquals(counts["txns"], back.txnQueries.countAll().executeAsOne())
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("pbk").toFile().also { it.deleteOnExit() }

    /** I4: Drive runs are logged apart, so a failed upload shows as pending without touching local status. */
    @Test fun driveStatusIsTrackedApartFromLocal() {
        val db = createDatabase(app.piggybank.data.DriverFactory().create())
        val svc = BackupService(db)
        assertNull(svc.driveAccount)
        svc.driveAccount = "someone@example.com"
        assertEquals("someone@example.com", svc.driveAccount)
        svc.log(1_000, "piggybank-2026-11-23.pbk", "LOCAL", "content://x", 10, null, 1, null)
        svc.log(1_001, "piggybank-2026-11-23.pbk", "DRIVE", null, null, null, 1, "Unable to resolve host")
        assertEquals(1_000L, svc.status().lastOkAt)
        assertEquals(0, svc.status().failingNights)
        assertEquals("Unable to resolve host", svc.status("DRIVE").lastError)
        svc.driveAccount = null
        assertNull(svc.driveAccount)
    }
}
