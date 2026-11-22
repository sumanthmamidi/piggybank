package app.piggybank.review

import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import app.piggybank.sms.IncomingSms
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Classification order and review decisions (TEST_CASES D, E, F, A6, C). Synthetic SMS only. */
class ReviewTest {
    private val tz = TimeZone.of("Asia/Kolkata")
    private lateinit var db: PiggyDatabase
    private lateinit var review: ReviewService
    private var nextId = 1L

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
        review = ReviewService(db, tz) { 1_000L }
    }

    private fun at(d: Int, h: Int = 10, mi: Int = 0, mo: Int = 11) =
        LocalDateTime(2026, mo, d, h, mi).toInstant(tz).toEpochMilliseconds()

    /** Ingests SMS then classifies, like the app does after every scan. */
    private fun receive(vararg sms: Pair<String, Long>, sender: String = "VM-HDFCBK") {
        SmsIngestor(db, tz).ingest(sms.map { (body, t) -> IncomingSms(nextId++, sender, body, t, t) })
        Classifier(db, now = { 1_000L }, tz = tz).run()
    }

    private fun txns() = db.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList()
    private fun only() = txns().single()
    private fun category(name: String) = db.categoryQueries.selectByName(name).executeAsOne().id

    /** Self-transfers are only ever marked by the user: detected ones are suggested and still count. */
    @Test fun d1DebitPairedWithCreditInOwnAccountIsSuggestedAsSelfTransfer() {
        receive(
            "RTGS txn initiated: Of Rs.400000 from your HDFC Bank A/c XX1111 using Online Banking." to at(21, 8, 20),
            "INR 400000.00 credited to Axis Bank A/c XX2222 on 21-11-26 at 08:23:48 IST." to at(21, 8, 24),
        )
        val t = only()
        assertEquals("SPEND", t.kind) // still counted …
        assertEquals("SELF_TRANSFER", t.suggested_kind) // … but suggested
        assertEquals("Money arrived in your account ••2222", t.suggest_reason)
        assertNull(t.reviewed_at) // waits for the user
        review.tag(listOf(t.id), "SELF_TRANSFER", category("Self-transfer"), null, remember = false)
        assertEquals("SELF_TRANSFER", only().kind)
    }

    @Test fun d2DebitNamingOwnAccountIsSuggestedAsSelfTransfer() {
        receive("Rs.500 debited from A/c XX2222 to VPA cafe@okaxis on 01-11-26" to at(1)) // XX2222 becomes known as own
        receive("IMPS INR 70,211.00 sent from HDFC Bank A/c XX1111 on 21-11-26 To A/c xxxxxxxxxx2222 Ref-6275" to at(21))
        val t = txns().first()
        assertEquals("SPEND", t.kind)
        assertEquals("SELF_TRANSFER", t.suggested_kind)
        assertNull(t.reviewed_at)
    }

    @Test fun d3TransferToSomeoneElseGoesToReview() {
        receive("IMPS INR 20,000.00 sent from HDFC Bank A/c XX1111 on 21-11-26 To A/c xxxxxxxxxx9999 Ref-6275" to at(21))
        val t = only()
        assertEquals("SPEND", t.kind)
        assertNull(t.reviewed_at)
    }

    @Test fun d4CardBillPaidAndReceivedOnCard() {
        receive(
            "Sent Rs.18400.00 From HDFC Bank A/C *1111 To CRED Club On 05/12/26 Ref 6236" to at(5, 9, mo = 12),
            "Dear Cardmember, payment of Rs.18,400.00 is received towards your YES BANK Credit Card ending 3333." to at(6, 11, mo = 12),
        )
        assertEquals("CC_BILL", only().kind)
    }

    @Test fun d4CardBillByName() {
        receive("Sent Rs.3000.00 From HDFC Bank A/C *1111 To INDmoney Credit Card Repa On 05/12/26 Ref 6236" to at(5, mo = 12))
        assertEquals("CC_BILL", only().kind)
    }

    @Test fun d8MutualFundIsAnInvestmentGuess() {
        receive("Additional Purchase txn of Rs.5000, dtd 19/11/2026 has been processed under folio XXXXXXXX5050." to at(19), sender = "VM-NIMFND")
        val t = only()
        assertEquals("INVESTMENT", t.kind)
        assertEquals("GUESS", t.tag_source)
        assertNull(t.reviewed_at)
    }

    @Test fun d9CountItMovesAHiddenItemIntoSpends() {
        receive("Sent Rs.3000.00 From HDFC Bank A/C *1111 To INDmoney Credit Card Repa On 05/12/26 Ref 6236" to at(5, mo = 12))
        review.countIt(only().id)
        assertEquals("SPEND", only().kind)
        assertNull(only().reviewed_at)
    }

    private val swiggyCard = "Rs.420.00 spent on HDFC Bank Card x4421 at SWIGGY on 20-11-26."

    @Test fun e1RememberedTagAppliesToTheNextPayment() {
        receive(swiggyCard to at(20))
        review.tag(listOf(only().id), "SPEND", category("Food delivery"), "WANT", remember = true)
        receive("Rs.380.00 spent on HDFC Bank Card x4421 at SWIGGY on 21-11-26." to at(21))
        val newest = txns().first()
        assertEquals(category("Food delivery"), newest.category_id)
        assertEquals("RULE", newest.tag_source)
        assertNotNull(newest.reviewed_at)
    }

    @Test fun e2OneRuleForCardAndUpi() {
        receive(swiggyCard to at(20))
        review.tag(listOf(only().id), "SPEND", category("Food delivery"), "WANT", remember = true)
        receive("Rs.250.00 debited from A/c XX1111 to VPA swiggy@icici on 21-11-26" to at(21))
        assertEquals("RULE", txns().first().tag_source)
    }

    @Test fun e3WithoutRememberNoRule() {
        receive(swiggyCard to at(20))
        review.tag(listOf(only().id), "SPEND", category("Food delivery"), "WANT", remember = false)
        assertTrue(db.ruleQueries.selectAll().executeAsList().isEmpty())
        receive("Rs.380.00 spent on HDFC Bank Card x4421 at SWIGGY on 21-11-26." to at(21))
        assertNull(txns().first().reviewed_at)
    }

    @Test fun e4PeopleAreNeverGuessed() {
        assertTrue(Classifier.looksLikePerson("RAMESH MUKHIYA", null))
        assertTrue(Classifier.looksLikePerson(null, "9876543210@ybl"))
        assertFalse(Classifier.looksLikePerson("KABAB STORIES", null))
        receive("Sent Rs.306.00 From HDFC Bank A/C *1111 To RAMESH MUKHIYA On 21/11/26 Ref 6236" to at(21))
        assertNull(only().category_id)
    }

    @Test fun e5AlwaysAskKeepsItInReview() {
        receive("Rs.999.00 spent on HDFC Bank Card x4421 at AMAZON on 20-11-26." to at(20))
        review.tag(listOf(only().id), "SPEND", category("Shopping"), "WANT", remember = true)
        review.setAlwaysAsk(db.ruleQueries.selectAll().executeAsOne().id, true)
        receive("Rs.199.00 spent on HDFC Bank Card x4421 at AMAZON on 21-11-26." to at(21))
        val newest = txns().first()
        assertEquals(category("Shopping"), newest.category_id) // pre-filled …
        assertNull(newest.reviewed_at) // … but still asked
    }

    @Test fun e7ExactUpiIdBeatsName() {
        receive("Rs.100.00 debited from A/c XX1111 to VPA ravi.cafe@okaxis on 20-11-26" to at(20))
        review.tag(listOf(only().id), "SPEND", category("Cafés"), "WANT", remember = true)
        db.ruleQueries.insert("MERCHANT", "EXACT", "RAVI CAFE", "SPEND", category("Groceries"), "NEED", 0, 0, null)
        receive("Rs.120.00 debited from A/c XX1111 to VPA ravi.cafe@okaxis on 21-11-26" to at(21))
        assertEquals(category("Cafés"), txns().first().category_id)
    }

    @Test fun e8InvestmentsHaveNoNeedWant() {
        receive(swiggyCard to at(20))
        review.tag(listOf(only().id), "INVESTMENT", category("Stocks"), "WANT", remember = false)
        assertNull(only().need_want)
    }

    @Test fun c1ConfirmAllowsTheFormatAndC2NotMoneyBlocksIt() {
        receive(swiggyCard to at(20))
        review.tag(listOf(only().id), "SPEND", category("Food delivery"), "WANT", remember = false)
        val tpl = db.smsQueries.selectByStatus("PARSED").executeAsOne().template_id!!
        assertEquals("WHITELIST", db.smsQueries.selectTemplates().executeAsList().first { it.id == tpl }.verdict)

        receive("Get ₹500 cashback on your next order! Use code FEST500" to at(20), "Get ₹200 cashback on your next order! Use code NEW200" to at(21))
        val first = db.smsQueries.selectByStatus("CANDIDATE").executeAsList().first()
        review.notMoney(first.id)
        assertTrue(db.smsQueries.selectByStatus("CANDIDATE").executeAsList().isEmpty()) // its twin settled too
        receive("Get ₹300 cashback on your next order! Use code BIG300" to at(22))
        assertTrue(db.smsQueries.selectByStatus("CANDIDATE").executeAsList().isEmpty()) // skipped silently
    }

    @Test fun f5PickingACandidateMakesItATransaction() {
        receive("Hi there, Your fee payment of Rs. 2600 to ABC SCHOOL via CoFee was successful." to at(21))
        val sms = db.smsQueries.selectByStatus("CANDIDATE").executeAsList().single()
        val id = review.pickCandidate(sms.id)!!
        assertEquals(260_000, db.txnQueries.selectById(id).executeAsOne().orig_amount_minor)
        assertEquals("USER_PICKED", db.smsQueries.selectSmsById(sms.id).executeAsOne().status)
    }

    private val autoPay = "AutoPay (E-mandate) Success!\nFor Google Play\nTxn Amt:INR149.00\nDt:05/11/2026\nVia:HDFC Bank CC 4444"
    private val cardSpend = "Rs.149 spent on HDFC Bank Card x4444 At GOOGLEPLAY On 2026-11-05:17:15:26."

    @Test fun a6eSameBankTwoFormatsIsMarkedAutomatically() {
        receive(autoPay to at(5, 17, 15), cardSpend to at(5, 17, 16))
        val dup = txns().single { it.duplicate_of != null }
        assertEquals("AUTO", dup.duplicate_by)
        assertEquals("GOOGLEPLAY", txns().single { it.duplicate_of == null }.counterparty_raw) // the one naming the payee stays
    }

    @Test fun a6eAlertAndUpdateHoursApart() {
        receive(
            "UPDATE: INR 10,029.00 debited from HDFC Bank XX1111 on 19-NOV-26. Info: ACH D- AcmeStandardLifeInsu-2900." to at(19, 8, 28),
            "PAYMENT ALERT!  INR 10029.00 deducted from HDFC Bank A/C No 1111 towards Acme Life Insurance Company Lt UMRN: HDFC70" to at(19, 11, 17),
        )
        assertEquals(1, txns().count { it.duplicate_by == "AUTO" })
    }

    @Test fun sameFormatSameDayIsNeverADuplicate() {
        // Four ATM withdrawals a minute apart: same SMS format, the balance drops each time.
        val atm = (0..3).map { i ->
            "INR 10000.00 debited from A/c no. XX932222 on FBL ATM 19-09-2026 16:5$i:07 IST. Avl bal: INR ${115 - 10 * i},199.16." to at(19, 16, 50 + i, mo = 9)
        }
        receive(*atm.toTypedArray(), sender = "VM-AXISBK")
        assertTrue(txns().all { it.duplicate_of == null })
        txns().forEach { assertNull(db.txnQueries.selectPossibleDuplicate(it.id).executeAsOneOrNull()) } // and never asked
    }

    @Test fun twoPeopleSameAmountSameDayAreNotDuplicates() {
        receive(
            "Sent Rs.70.00\nFrom HDFC Bank A/C *1111\nTo C KUMAR\nOn 10/11/26\nRef 6314633" to at(10, 14, 5),
            "Sent Rs.70.00\nFrom HDFC Bank A/C *1111\nTo Mr Raju\nOn 10/11/26\nRef 6314642" to at(10, 15, 24),
            sender = "AD-HDFCBK-T",
        )
        assertTrue(txns().all { it.duplicate_of == null })
        val templates = db.smsQueries.selectByStatus("PARSED").executeAsList().map { it.template_id }.toSet()
        assertEquals(1, templates.size) // "Mr" doesn't make a new format
    }

    @Test fun a6aDifferentSendersAreAskedNotMarked() {
        receive(swiggyCard to at(20, 10, 0))
        receive("Paid Rs.420 to Swiggy via UPI. UPI Ref 6236" to at(20, 10, 3), sender = "VM-PAYAPP")
        val (a, b) = txns().map { it.id }.sorted()
        assertTrue(txns().all { it.duplicate_of == null })
        assertEquals(a, db.txnQueries.selectPossibleDuplicate(b).executeAsOneOrNull()?.id)
        review.separatePayments(a, b) // A6d
        assertNull(db.txnQueries.selectPossibleDuplicate(b).executeAsOneOrNull())
    }

    @Test fun a6cUndoneAutoDuplicateIsNeverMarkedAgain() {
        receive(autoPay to at(5, 17, 15), cardSpend to at(5, 17, 16))
        review.undoDuplicate(txns().single { it.duplicate_of != null }.id)
        receive("Rs.99.00 spent on HDFC Bank Card x4421 at CAFE on 22-11-26." to at(22)) // triggers another run
        assertTrue(txns().all { it.duplicate_of == null })
    }

    @Test fun a6bManualDuplicateLeavesTotals() {
        receive(swiggyCard to at(20, 10, 0))
        receive("Paid Rs.420 to Swiggy via UPI. UPI Ref 6236" to at(20, 10, 3), sender = "VM-PAYAPP")
        val (a, b) = txns().map { it.id }.sorted()
        review.markDuplicate(b, a)
        assertEquals(1, txns().count { it.duplicate_of == null })
    }

    @Test fun f2f4Streak() {
        val d = LocalDate(2026, 11, 20)
        assertEquals(1, review.closeDay(d, 3))
        assertEquals(2, review.closeDay(LocalDate(2026, 11, 21), 0)) // F3: nothing to review still counts
        assertEquals(2, review.streak(LocalDate(2026, 11, 22)))
        assertEquals(0, review.streak(LocalDate(2026, 11, 23))) // skipped a day
        assertEquals(1, review.closeDay(LocalDate(2026, 11, 23), 1))
    }

    @Test fun notAPaymentRemovesItAndBlocksTheFormat() {
        receive(swiggyCard to at(20))
        review.notAPayment(only().id)
        assertTrue(txns().isEmpty())
        receive("Rs.380.00 spent on HDFC Bank Card x4421 at SWIGGY on 21-11-26." to at(21))
        assertTrue(txns().isEmpty())
    }
}
