package app.piggybank.payments

import app.piggybank.core.YearMonth
import app.piggybank.db.PiggyDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

enum class PaymentSort { LATEST, HIGHEST }

/** Which payments of the list: the "type" chip. Needs/Wants are spends split by Need/Want. */
enum class PaymentGroup(val label: String, val kinds: List<String>, val needWant: String?) {
    ALL("All", listOf("SPEND", "INVESTMENT", "SELF_TRANSFER", "CC_BILL", "WALLET_TOPUP"), null),
    SPENDS("Spends", listOf("SPEND"), null),
    NEEDS("Needs", listOf("SPEND"), "NEED"),
    WANTS("Wants", listOf("SPEND"), "WANT"),
    INVESTMENTS("Investments", listOf("INVESTMENT"), null),
    HIDDEN("Hidden", listOf("SELF_TRANSFER", "CC_BILL", "WALLET_TOPUP"), null),
}

/** What the Payments screen shows. [month] null = all time. */
data class PaymentsFilter(
    val month: YearMonth? = null,
    val categoryId: Long? = null,
    val group: PaymentGroup = PaymentGroup.ALL,
    val sort: PaymentSort = PaymentSort.LATEST,
)

data class PaymentRow(
    val id: Long,
    val occurredAt: Long,
    val origCurrency: String,
    val origMinor: Long,
    /** ₹ amount after refunds; null for a foreign payment still waiting for a rate. */
    val netHomeMinor: Long?,
    val kind: String,
    val categoryId: Long?,
    val categoryName: String?,
    val needWant: String?,
    val auto: Boolean,
    val payee: String?,
    val institution: String?,
    val last4: String?,
)

/** The tagged payments list (and the Monthly → category drill-down). */
class PaymentsService(private val db: PiggyDatabase) {

    fun list(f: PaymentsFilter, tz: TimeZone): List<PaymentRow> {
        val (from, until) = f.month?.let { m ->
            val start = LocalDate(m.year, m.month, 1).atStartOfDayIn(tz).toEpochMilliseconds()
            val n = m.next()
            start to LocalDate(n.year, n.month, 1).atStartOfDayIn(tz).toEpochMilliseconds()
        } ?: (0L to Long.MAX_VALUE)
        val q = db.txnQueries
        val rows = when (f.sort) {
            PaymentSort.LATEST -> q.selectPayments(from, until, f.categoryId, f.group.needWant, f.group.kinds).executeAsList().map {
                PaymentRow(it.id, it.occurred_at, it.orig_currency, it.orig_amount_minor, it.home_amount_minor?.let { h -> h - it.refund_minor },
                    it.kind, it.category_id, it.category_name, it.need_want, it.tag_source == "RULE", it.counterparty_raw, it.institution, it.last4)
            }
            PaymentSort.HIGHEST -> q.selectPaymentsByAmount(from, until, f.categoryId, f.group.needWant, f.group.kinds).executeAsList().map {
                PaymentRow(it.id, it.occurred_at, it.orig_currency, it.orig_amount_minor, it.home_amount_minor?.let { h -> h - it.refund_minor },
                    it.kind, it.category_id, it.category_name, it.need_want, it.tag_source == "RULE", it.counterparty_raw, it.institution, it.last4)
            }
        }
        return rows
    }
}
