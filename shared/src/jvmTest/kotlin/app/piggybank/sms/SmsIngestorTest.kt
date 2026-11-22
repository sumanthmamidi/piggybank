package app.piggybank.sms

import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmsIngestorTest {
    private val tz = TimeZone.of("Asia/Kolkata")
    private lateinit var db: PiggyDatabase
    private lateinit var ingestor: SmsIngestor

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
        ingestor = SmsIngestor(db, tz)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int = 20, mi: Int = 0) =
        LocalDateTime(y, mo, d, h, mi).toInstant(tz).toEpochMilliseconds()

    private var nextId = 1L
    private fun sms(body: String, received: Long = at(2026, 11, 21), sender: String = "VM-HDFCBK", sent: Long = received) =
        IncomingSms(nextId++, sender, body, received, sent)

    private val coffee = "Rs.320.00 spent on HDFC Bank Card x4421 at BLUE TOKAI on 21-11-26. Avl Lmt: Rs 1,23,456.78"

    @Test fun paymentIsStoredWithItsDetails() { // A1, B1
        val s = ingestor.ingest(listOf(sms(coffee)))
        assertEquals(1, s.payments)
        val t = db.txnQueries.selectRecentWithAccount(10).executeAsList().single()
        assertEquals(32_000, t.home_amount_minor)
        assertEquals("BLUE TOKAI", t.counterparty_raw)
        assertEquals("4421", t.last4)
        assertEquals("CARD", t.account_kind)
        assertEquals("HDFCBK", t.institution)
    }

    @Test fun a4NonMoneySmsIsNotStored() {
        val s = ingestor.ingest(listOf(sms("Your card statement is ready")))
        assertEquals(1, s.notMoney)
        assertEquals(0L, db.smsQueries.countSms().executeAsOne())
    }

    @Test fun a6RescanAndRedeliveryStoreOnce() {
        val first = sms(coffee)
        ingestor.ingest(listOf(first))
        // Inbox scanned again, and the carrier delivered a second copy (new inbox id, same send time).
        val again = ingestor.ingest(listOf(first, first.copy(androidId = 99, receivedAt = first.receivedAt + 40_000)))
        assertEquals(2, again.alreadyStored)
        assertEquals(1L, db.txnQueries.countAll().executeAsOne())
    }

    @Test fun twoIdenticalPaymentsAtDifferentTimesAreBothKept() {
        ingestor.ingest(listOf(sms(coffee, at(2026, 11, 21, 9)), sms(coffee, at(2026, 11, 21, 17))))
        assertEquals(2L, db.txnQueries.countAll().executeAsOne())
    }

    @Test fun b5CreditIsKeptButNotATransaction() {
        val s = ingestor.ingest(listOf(sms("Rs 2,000 credited to A/c XX9910 on 21-11-26 by NEFT")))
        assertEquals(1, s.ignored)
        assertEquals(0L, db.txnQueries.countAll().executeAsOne())
        assertEquals(1, db.smsQueries.selectByStatus("IGNORED").executeAsList().size)
    }

    @Test fun unclearSmsBecomesACandidate() { // "Missed a payment?"
        ingestor.ingest(listOf(sms("OTP 482910 for txn of Rs 5,000 at AMAZON")))
        assertEquals(1, db.smsQueries.selectByStatus("CANDIDATE").executeAsList().size)
    }

    @Test fun a3BlacklistedFormatIsSkippedSilently() {
        ingestor.ingest(listOf(sms("OTP 482910 for txn of Rs 5,000 at AMAZON")))
        val tpl = db.smsQueries.selectByStatus("CANDIDATE").executeAsList().single().template_id!!
        db.smsQueries.setTemplateVerdict("BLACKLIST", "USER", 0, tpl)
        val s = ingestor.ingest(listOf(sms("OTP 771234 for txn of Rs 899 at AMAZON")))
        assertEquals(1, s.ignored)
        assertEquals(0, s.candidates)
    }

    @Test fun c3SameFormatSharesOneTemplate() {
        ingestor.ingest(listOf(
            sms(coffee),
            sms("Rs.1,849.00 spent on HDFC Bank Card x9012 at STARBUCKS COFFEE on 03-12-26. Avl Lmt: Rs 98,000.00", at(2026, 12, 3)),
        ))
        val ids = db.smsQueries.selectByStatus("PARSED").executeAsList().map { it.template_id }.toSet()
        assertEquals(1, ids.size)
    }

    @Test fun b7DateInSmsWinsAndMissingDateUsesArrival() {
        val received = at(2026, 11, 21, 9, 30)
        ingestor.ingest(listOf(
            sms("Rs.500 spent on HDFC Bank Card x4421 at CHAI POINT on 18-11-26", received),
            sms("Rs.600 spent on HDFC Bank Card x4421 at CHAI POINT", received + 1000),
        ))
        val rows = db.txnQueries.selectRecentWithAccount(10).executeAsList().sortedBy { it.orig_amount_minor }
        assertEquals("SMS", rows[0].date_source)
        assertEquals(at(2026, 11, 18, 12, 0), rows[0].occurred_at)
        assertEquals("ARRIVAL", rows[1].date_source)
        assertEquals(received + 1000, rows[1].occurred_at)
    }

    @Test fun m1ToM3ForeignCurrencies() {
        db.moneyQueries.upsertRate("EUR", "90.20", 0)
        ingestor.ingest(listOf(
            sms("USD 25.00 spent on HDFC Card x4421 at AWS. INR equivalent 2,113.40"),
            sms("EUR 40.00 spent on card x4421 at ZARA PARIS"),
            sms("GBP 10.00 spent on card x4421 at TESCO"),
        ))
        val rows = db.txnQueries.selectRecentWithAccount(10).executeAsList().associateBy { it.orig_currency }
        assertEquals(211_340, rows["USD"]!!.home_amount_minor)
        assertEquals(360_800, rows["EUR"]!!.home_amount_minor)
        assertNull(rows["GBP"]!!.home_amount_minor) // "₹ amount needed", left out of totals
        assertEquals(0L, db.txnQueries.spendTotalBetween(0, Long.MAX_VALUE).executeAsOne() - 211_340 - 360_800)
    }
}
