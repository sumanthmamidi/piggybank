package app.piggybank.payments

import app.piggybank.core.YearMonth
import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import app.piggybank.month.MonthService
import app.piggybank.review.Classifier
import app.piggybank.review.ReviewService
import app.piggybank.sms.IncomingSms
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Payments list and the Monthly → category drill-down. Synthetic SMS only. */
class PaymentsServiceTest {
    private val tz = TimeZone.of("Asia/Kolkata")
    private lateinit var db: PiggyDatabase
    private lateinit var review: ReviewService
    private lateinit var payments: PaymentsService
    private var nextId = 1L

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
        review = ReviewService(db, tz) { 1_000L }
        payments = PaymentsService(db)
    }

    private fun at(d: Int, mo: Int = 11) = LocalDateTime(2026, mo, d, 10, 0).toInstant(tz).toEpochMilliseconds()
    private fun cat(name: String) = db.categoryQueries.selectByName(name).executeAsOne().id

    private fun pay(rupees: Int, vpa: String, day: Int, mo: Int = 11): Long {
        val t = at(day, mo)
        SmsIngestor(db, tz).ingest(listOf(IncomingSms(nextId++, "VM-HDFCBK", "Rs.$rupees.00 debited from A/c XX1111 to VPA $vpa on $day-$mo-26.", t, t)))
        Classifier(db, now = { t }, tz = tz).run()
        return db.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList().maxBy { it.id }.id
    }

    private fun tag(id: Long, category: String, nw: String?, kind: String = "SPEND") = review.tag(listOf(id), kind, cat(category), nw, remember = false)

    @Test fun onlyTaggedPaymentsNewestFirstOrBiggestFirst() {
        val a = pay(120, "cafe.one@okaxis", 3); tag(a, "Cafés", "WANT")
        val b = pay(900, "grocer@okaxis", 5); tag(b, "Groceries", "NEED")
        val c = pay(300, "cafe.two@okaxis", 7); tag(c, "Cafés", "WANT")
        pay(50, "someone@okaxis", 8) // untagged: stays in Review, not in the list

        assertEquals(listOf(c, b, a), payments.list(PaymentsFilter(), tz).map { it.id })
        assertEquals(listOf(b, c, a), payments.list(PaymentsFilter(sort = PaymentSort.HIGHEST), tz).map { it.id })
    }

    @Test fun drillDownByMonthCategoryAndNeedWant() {
        val oct = pay(400, "cafe.one@okaxis", 30, mo = 10); tag(oct, "Cafés", "WANT")
        val nov1 = pay(120, "cafe.one@okaxis", 3); tag(nov1, "Cafés", "WANT")
        val nov2 = pay(900, "grocer@okaxis", 5); tag(nov2, "Groceries", "NEED")
        val sip = pay(5000, "mf.house@okaxis", 6); tag(sip, "Mutual fund SIP", null, kind = "INVESTMENT")

        val nov = YearMonth(2026, 11)
        assertEquals(listOf(nov1), payments.list(PaymentsFilter(month = nov, categoryId = cat("Cafés"), group = PaymentGroup.SPENDS), tz).map { it.id })
        assertEquals(listOf(oct), payments.list(PaymentsFilter(month = YearMonth(2026, 10), categoryId = cat("Cafés")), tz).map { it.id })
        assertEquals(listOf(nov2), payments.list(PaymentsFilter(month = nov, group = PaymentGroup.NEEDS), tz).map { it.id })
        assertEquals(listOf(sip), payments.list(PaymentsFilter(month = nov, group = PaymentGroup.INVESTMENTS), tz).map { it.id })
        assertEquals(setOf(nov1, nov2), payments.list(PaymentsFilter(month = nov, group = PaymentGroup.SPENDS), tz).map { it.id }.toSet())
    }

    @Test fun amountsAreAfterRefundsAndMatchTheMonthlyCategoryTotal() {
        val a = pay(500, "cafe.one@okaxis", 3); tag(a, "Cafés", "WANT")
        val b = pay(200, "cafe.two@okaxis", 4); tag(b, "Cafés", "WANT")
        MonthService(db) { 0 }.markRefund(a, 150_00)
        val rows = payments.list(PaymentsFilter(month = YearMonth(2026, 11), categoryId = cat("Cafés")), tz)
        assertEquals(350_00L + 200_00L, rows.sumOf { it.netHomeMinor!! })
        val line = MonthService(db) { 0 }.report(YearMonth(2026, 11), kotlinx.datetime.LocalDate(2026, 11, 30), tz)
            .categories.firstOrNull { it.categoryId == cat("Cafés") }
        line?.let { assertEquals(it.spent, rows.sumOf { r -> r.netHomeMinor!! }) }
    }

    @Test fun changeTagMovesAPaymentBetweenCategories() {
        val a = pay(120, "cafe.one@okaxis", 3); tag(a, "Cafés", "WANT")
        review.tag(listOf(a), "SPEND", cat("Groceries"), "NEED", remember = false) // what "Change tag" does
        assertEquals(emptyList(), payments.list(PaymentsFilter(categoryId = cat("Cafés")), tz))
        assertEquals(listOf(a), payments.list(PaymentsFilter(categoryId = cat("Groceries")), tz).map { it.id })
        assertEquals(0, db.ruleQueries.selectAll().executeAsList().size)
    }
}
