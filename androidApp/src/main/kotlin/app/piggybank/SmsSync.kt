package app.piggybank

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import app.piggybank.db.PiggyDatabase
import app.piggybank.review.Classifier
import app.piggybank.sms.IncomingSms
import app.piggybank.sms.IngestSummary
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.TimeZone
import java.util.Calendar

/**
 * The phone's SMS inbox is the single source: the live receiver, app open and (later) the nightly
 * job all run the same scan, and dedupe makes repeated scans harmless (A5, A6). Both SIMs land in
 * the same inbox (A9).
 */
object SmsSync {
    private const val DAY_MS = 86_400_000L
    private const val CHUNK = 500
    private const val META_LAST_SCAN = "last_sms_scan_at"
    private const val META_HISTORY = "history_months"

    /** Set by the open screen so it refreshes when the receiver stores something. */
    @Volatile var onChange: (() -> Unit)? = null

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun importDone(db: PiggyDatabase) = db.appQueries.getMeta(META_HISTORY).executeAsOneOrNull() != null

    /** First run: read the last [months] months (A7). */
    @Synchronized
    fun importHistory(context: Context, db: PiggyDatabase, months: Int): IngestSummary {
        val since = Calendar.getInstance().apply { add(Calendar.MONTH, -months) }.timeInMillis
        val result = scan(context, db, since)
        db.appQueries.putMeta(META_HISTORY, months.toString())
        return result
    }

    /** Anything newer than the last scan, with a day of overlap for late-written messages. */
    @Synchronized
    fun catchUp(context: Context, db: PiggyDatabase): IngestSummary? {
        if (!hasPermission(context) || !importDone(db)) return null
        val last = db.appQueries.getMeta(META_LAST_SCAN).executeAsOneOrNull()?.toLongOrNull() ?: return null
        return scan(context, db, last - DAY_MS)
    }

    private fun scan(context: Context, db: PiggyDatabase, since: Long): IngestSummary {
        val startedAt = System.currentTimeMillis()
        val ingestor = SmsIngestor(db, TimeZone.currentSystemDefault())
        var total = IngestSummary()
        val batch = ArrayList<IncomingSms>(CHUNK)
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(2) ?: continue
                batch += IncomingSms(
                    androidId = c.getLong(0),
                    sender = c.getString(1) ?: "UNKNOWN",
                    body = body,
                    receivedAt = c.getLong(3),
                    sentAt = c.getLong(4),
                )
                if (batch.size == CHUNK) {
                    total += ingestor.ingest(batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) total += ingestor.ingest(batch)
        // Tag what can be tagged (transfers, card bills, rules) right away, so a payment that
        // matches a rule never needs review, even if it arrived while the app was closed (E1).
        Classifier(db).run()
        db.appQueries.putMeta(META_LAST_SCAN, startedAt.toString())
        if (total.payments + total.candidates > 0) onChange?.invoke()
        return total
    }
}
