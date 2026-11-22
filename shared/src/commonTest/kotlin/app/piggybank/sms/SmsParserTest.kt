package app.piggybank.sms

import app.piggybank.core.Currency
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic SMS only. Real messages go in the git-ignored testdata/private/ folder. */
class SmsParserTest {
    private val inr = Currency.INR
    private fun parse(body: String) = SmsParser.parse(body, inr)

    @Test fun a1CardSpend() {
        val p = parse("Rs.320.00 spent on HDFC Bank Card x4421 at BLUE TOKAI on 21-11-26. Avl Lmt: Rs 1,23,456.78")!!
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals(32_000, p.amount!!.minor)
        assertEquals("4421", p.accountLast4)
        assertTrue(p.accountIsCard)
        assertEquals("BLUE TOKAI", p.merchant)
        assertEquals(LocalDate(2026, 11, 21), p.date)
    }

    @Test fun a2UnknownSenderStillRead() {
        val p = parse("INR 1,250 debited from your account. Thank you.")!!
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals(125_000, p.amount!!.minor)
    }

    @Test fun a3OtpIsNeverAPayment() {
        val p = parse("OTP 482910 for txn of Rs 5,000 at AMAZON. Do not share.")!!
        assertEquals(Direction.UNKNOWN, p.direction)
        assertFalse(p.isPayment)
    }

    @Test fun a4NoAmountIsNotMoney() {
        assertNull(parse("Your card statement is ready. Login to view."))
        assertNull(parse("Meeting at 10:30 on 21-11-26"))
    }

    @Test fun b1UpiDebit() {
        val p = parse("Rs.1,164.50 debited from A/c XX4521 to VPA bigbasket@hdfcbank. Ref 432198765. -SBI")!!
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals(116_450, p.amount!!.minor)
        assertEquals("4521", p.accountLast4)
        assertFalse(p.accountIsCard)
        assertEquals("bigbasket@hdfcbank", p.vpa)
        assertEquals("bigbasket@hdfcbank", p.merchant)
    }

    @Test fun b2AmountSpellings() {
        listOf("INR 20000.00 debited", "Rs 20,000 debited", "₹20,000/- debited", "Rs.20000 debited", "INR20,000.00 debited")
            .forEach { assertEquals(2_000_000, parse(it)!!.amount!!.minor, it) }
    }

    @Test fun b3IndianGrouping() {
        assertEquals(15_000_000, parse("Rs 1,50,000.00 credited to A/c XX9910")!!.amount!!.minor)
    }

    @Test fun b4BalanceIsNeverTheAmount() {
        val p = parse("Avl Bal Rs 42,310.00. Your A/c XX4521 is debited Rs 500 on 21-11-26")!!
        assertEquals(50_000, p.amount!!.minor)
        val q = parse("A/c XX4521 debited Rs 500. Avl Bal Rs 42,310")!!
        assertEquals(50_000, q.amount!!.minor)
    }

    @Test fun b5CreditsAreMoneyInNotPayments() {
        val p = parse("Rs 2,000 credited to A/c XX9910 on 21-11-26 by NEFT from ACME PAYROLL")!!
        assertEquals(Direction.CREDIT, p.direction)
        assertFalse(p.isPayment)
    }

    @Test fun b6RefundIsMoneyIn() {
        assertEquals(Direction.CREDIT, parse("Refund of Rs 612 from SWIGGY processed to your card x4421")!!.direction)
    }

    @Test fun b7DateFormats() {
        assertEquals(LocalDate(2026, 11, 18), SmsParser.findDate("debited on 18/11/2026"))
        assertEquals(LocalDate(2026, 11, 18), SmsParser.findDate("debited on 18Nov26"))
        assertEquals(LocalDate(2026, 11, 18), SmsParser.findDate("debited on 18-Nov-2026"))
        assertEquals(LocalDate(2026, 11, 18), SmsParser.findDate("on 2026-11-18 at"))
        assertNull(SmsParser.findDate("debited on 31-02-26")) // invalid → arrival date (B7a)
        assertNull(SmsParser.findDate("debited Rs 500"))
        assertEquals(LocalTime(22, 15), SmsParser.findTime("at 10:15 PM"))
    }

    @Test fun bankFormatsDebit() {
        val cases = mapOf(
            "Sent Rs.486.00 From HDFC Bank A/C x4521 To UBER On 21/11/26 Ref 633012345678 Not You? Call 18002586161" to
                Triple(48_600L, "4521", "UBER"),
            "Dear Customer, Your A/C XXXXX4521 has a debit by transfer of Rs 1,164.50 on 21Nov26 transferred to ZOMATO." to
                Triple(116_450L, "4521", "ZOMATO"),
            "ICICI Bank Acct XX521 debited for Rs 1164.50 on 21-Nov-26; bigbasket credited. UPI:432198765. Call 18002662 for dispute." to
                Triple(116_450L, "521", "bigbasket"),
            "Rs.2,499.00 spent on your Axis Bank Credit Card XX7788 at AMAZON.IN on 21-11-26 10:05:33. Avl limit: Rs.45,000" to
                Triple(249_900L, "7788", "AMAZON.IN"),
            "Your Kotak Debit Card ending 1234 was used for INR 340.00 at DMART on 21-Nov-2026." to
                Triple(34_000L, "1234", "DMART"),
        )
        cases.forEach { (body, want) ->
            val p = parse(body)!!
            assertEquals(Direction.DEBIT, p.direction, body)
            assertEquals(want.first, p.amount!!.minor, body)
            assertEquals(want.second, p.accountLast4, body)
            assertEquals(want.third, p.merchant, body)
        }
    }

    /** Formats seen on a real phone (names, numbers and refs changed). */
    @Test fun realWorldFormats() {
        data class Want(val minor: Long, val last4: String?, val merchant: String?, val card: Boolean? = null)
        val cases = mapOf(
            "Sent Rs.80000.00 From HDFC Bank A/C *1111 To Ms. Asha Rani Kuma On 23/09/26 Ref 130000000001 Not You? Call 1800/SMS BLOCK UPI to 7300" to
                Want(8_000_000, "1111", "Asha Rani Kuma"),
            "Your A/C XXXXX392222 Debited INR 1,00,000.00 on 25/08/26 -Transferred to Mr. Ravi Kumar. Avl Balance INR 8,11,292.56-SBI" to
                Want(10_000_000, "2222", "Ravi Kumar"),
            "INR 130.00 spent on YES BANK Card X3333 @UPI_HIGH ON CHAI PREST 12-11-2026 05:49:45 pm. Avl Lmt INR 355,746.87." to
                Want(13_000, "3333", "HIGH ON CHAI PREST", true),
            "INR 70.00 spent on YES BANK Card X3333 @UPI_RAMESH STORES 03-09-2026 08:56:34 pm. Avl Lmt INR 389,058.46. SMS BLKCC 3333 to 9800000000 if not you" to
                Want(7_000, "3333", "RAMESH STORES", true),
            "Spent Rs.2751 On HDFC Bank Card 4444 At ..BOBS BAR INDIRAN_ On 2026-10-01:23:26:02.Not You? To Block+Reissue Call 1800/SMS BLOCK CC 4444 to 7300" to
                Want(275_100, "4444", "BOBS BAR INDIRAN", true),
            "Amount Spent: Rs.650 with HDFC Bank Credit Card 4444 at GOOGLEPLAY on 2026-09-28:11:13:30 This transaction was completed" to
                Want(65_000, "4444", "GOOGLEPLAY", true),
            "Dear Customer, Rs 1,596.92 debited from ICICI Bank Prepaid Card 5555 on 07-Nov-26. Info- ZOMATO LIMITE. The Available Balance is Rs 13,264.51 ." to
                Want(159_692, "5555", "ZOMATO LIMITE", true),
            "PAYMENT ALERT!  INR 750.00 deducted from HDFC Bank A/C No 1111 towards CHILD WELFARE FUND UMRN: HDFC7000000000000001" to
                Want(75_000, "1111", "CHILD WELFARE FUND", false),
            "INR 1,434.00 spent using ICICI Bank Card XX6666 on 31-Aug-26 on AMAZON PAY IN E. Avl Limit: INR 1,05,177.26. If not you, call 1800" to
                Want(143_400, "6666", "AMAZON PAY IN E", true),
            "Debit INR 1294.46 Axis Bank A/c XX7777 11-09-26 07:53:49 AUTOBPay/AirtelPost/625409 WhatsApp BAL to 910000000000 Not You?" to
                Want(129_446, "7777", "AirtelPost", false),
            "Your Ac Xx2222 debited Rs.2,05,000.00 for transfer to G Hari Ac Xx8888 dt 25.08.26 Ref 623700000000. If not done by you, call 1800" to
                Want(20_500_000, "2222", "G Hari"),
            "UPI Mandate: Sent Rs.1235.46 from HDFC Bank A/c 1111 To ET Money 25/10/26 Ref 110000000000 Not You? Call 1800" to
                Want(123_546, "1111", "ET Money"),
            "Transaction Successful! USD23.60 has been debited to your SBI Credit Card XX9999 against recurring e-mandate registered by you at merchant site. Details: Merchant: ACMECLOUDSUB SiHub ID: Yx00 Trxn. Ref. No.: 3962 Date: 04-Oct-2026." to
                Want(2_360, "9999", "ACMECLOUDSUB", true),
            "INR5,701.50 spent at ING*REDBUS INDIA PVT L on RBL Bank credit card (1212) on 28-08-2026.AVL limit- INR35,252.80." to
                Want(570_150, "1212", "ING*REDBUS INDIA PVT L", true),
            "AutoPay (E-mandate) Success!\nFor Google Play\nTxn Amt:INR149.00\nDt:05/09/2026\nVia:HDFC Bank CC 4444\nMandate ID: Y4gv" to
                Want(14_900, "4444", null, true),
            "Dear Customer, Standing Instruction has been successfully executed for Rs. 39,219.00 from your A/c No.XXXXX041111 to A/c No.XXXXX392222 on 01/10/26.  - SBI." to
                Want(3_921_900, "1111", null),
            "RTGS txn initiated: Of Rs.400000 from your HDFC Bank A/c XX1111 using Online Banking. Not you?Call 1800" to
                Want(40_000_000, "1111", null, false),
        )
        cases.forEach { (body, want) ->
            val p = parse(body)!!
            assertEquals(Direction.DEBIT, p.direction, body)
            assertEquals(want.minor, p.amount!!.minor, body)
            assertEquals(want.last4, p.accountLast4, body)
            if (want.merchant != null) assertEquals(want.merchant, p.merchant, body)
            want.card?.let { assertEquals(it, p.accountIsCard, body) }
        }
    }

    @Test fun realWorldNonPayments() {
        listOf(
            "Dear Customer, an auto-debit of INR 10029 has been initiated towards the premium due for Policy Number - 2900. The payment will be confirmed upon successful realisation.",
            "We have initiated a refund of Rs.267.73 for ORD5600 into your UPI, which should reflect in 3-5 business days.",
            "Dear customer, your amount Rs1898 /- has been initiated for refund. It will be credited back to you within 7 working days.",
        ).forEach { assertFalse(parse(it)!!.isPayment, it) }
        // A share's face value is not money that moved.
        assertNull(parse("CDSL: Debit in a/c *24900000 for 88-GMDC-EQ RS2/- on 17SEP")!!.amount)
        // "without OTP" is a completed card payment notice, not an OTP.
        assertEquals(Direction.DEBIT, parse("Rs.149 spent without OTP/PIN on HDFC Bank Card x4444 At GOOGLEPLAY")!!.direction)
    }

    @Test fun c3NamesDontSplitFormats() {
        val a = "INR 70.00 spent on YES BANK Card X3333 @UPI_RAMESH STORES 03-09-2026 08:56:34 pm. Avl Lmt INR 389,058.46. SMS BLKCC 3333 to 9800000000 if not you"
        val b = "INR 129.00 spent on YES BANK Card X3333 @UPI_M S FRESH MART 06-09-2026 10:54:28 am. Avl Lmt INR 388,364.46. SMS BLKCC 3333 to 9800000000 if not you"
        assertEquals(sig("VM-YESBNK", a), sig("VM-YESBNK", b))
    }

    @Test fun notCompletedPaymentsAreCandidates() {
        listOf(
            "Rs 499 will be debited on 25-11-26 from A/c XX4521 for NETFLIX mandate",
            "Your card bill of Rs 18,200 is due on 05-12-26. Min amt due Rs 910",
            "Transaction of Rs 2,000 at FLIPKART on card x4421 declined due to insufficient limit",
            "ABC has requested Rs 500 from you on UPI. Pay now",
            "Get ₹500 cashback on your next order! Use code FEST500",
        ).forEach { assertEquals(Direction.UNKNOWN, parse(it)!!.direction, it) }
    }

    @Test fun m1ForeignWithHomeEquivalent() {
        val p = parse("USD 25.00 spent on HDFC Card x4421 at AWS. INR equivalent 2,113.40")!!
        assertEquals("USD", p.amount!!.currency.code)
        assertEquals(2_500, p.amount!!.minor)
        assertEquals(211_340, p.homeAmount!!.minor)
    }

    @Test fun m2ForeignWithoutHomeAmount() {
        val p = parse("EUR 40.00 spent on card x4421 at ZARA PARIS")!!
        assertEquals("EUR", p.amount!!.currency.code)
        assertNull(p.homeAmount)
    }

    @Test fun m4Decimals() {
        assertEquals(1_200, parse("JPY 1,200 spent on card x4421 at LAWSON")!!.amount!!.minor)
        assertEquals(3_500, parse("KWD 3.500 spent on card x4421 at TALABAT")!!.amount!!.minor)
    }

    @Test fun m5SymbolsAndCodes() {
        mapOf("$" to "USD", "US$" to "USD", "USD " to "USD", "€" to "EUR", "EUR " to "EUR", "AED " to "AED", "SGD " to "SGD")
            .forEach { (token, code) -> assertEquals(code, parse("${token}10.00 spent on card x4421")!!.amount!!.currency.code, token) }
        assertTrue(parse("$10.00 spent on card x4421")!!.ambiguousCurrency)
        assertFalse(parse("US$10.00 spent on card x4421")!!.ambiguousCurrency)
    }

    @Test fun c3SameFormatSameSignature() {
        val a = "Rs.320.00 spent on HDFC Bank Card x4421 at BLUE TOKAI on 21-11-26. Avl Lmt: Rs 1,23,456.78"
        val b = "Rs.1,849.00 spent on HDFC Bank Card x9012 at STARBUCKS COFFEE on 03-12-26. Avl Lmt: Rs 98,000.00"
        assertEquals(sig("VM-HDFCBK", a), sig("AD-HDFCBK-S", b))
    }

    @Test fun c4PromoAndPaymentFromSameSenderDiffer() {
        val promo = "Get ₹500 cashback on your next order! Use code FEST500"
        val pay = "Rs.320.00 spent on HDFC Bank Card x4421 at BLUE TOKAI on 21-11-26."
        assertNotEquals(sig("VM-HDFCBK", promo), sig("VM-HDFCBK", pay))
        // Two promos with different amounts and codes are one format.
        assertEquals(sig("VM-HDFCBK", promo), sig("VM-HDFCBK", "Get ₹1,000 cashback on your next order! Use code BIG1000"))
    }

    @Test fun senderKeys() {
        assertEquals("HDFCBK", Signature.senderKey("VM-HDFCBK"))
        assertEquals("HDFCBK", Signature.senderKey("AD-HDFCBK-S"))
        assertEquals("PHONE", Signature.senderKey("+919876543210"))
    }

    @Test fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test fun rateMaths() {
        val usd = app.piggybank.core.Money(2_500, Currency.of("USD"))
        assertEquals("84.5360", SmsIngestor.rateString(usd, app.piggybank.core.Money(211_340, inr)))
        val eur = app.piggybank.core.Money(4_000, Currency.of("EUR"))
        assertEquals(360_800, SmsIngestor.convert(eur, "90.20", inr)!!.minor)
        val jpy = app.piggybank.core.Money(1_200, Currency.of("JPY"))
        assertEquals(66_600, SmsIngestor.convert(jpy, "0.555", inr)!!.minor)
    }

    private fun sig(sender: String, body: String): String {
        val key = Signature.senderKey(sender)
        return Signature.of(key, body, parse(body))
    }
}
