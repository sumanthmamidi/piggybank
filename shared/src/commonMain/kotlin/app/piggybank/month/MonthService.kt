package app.piggybank.month

import app.piggybank.core.Currency
import app.piggybank.core.Income
import app.piggybank.core.IncomeSchedule
import app.piggybank.core.Money
import app.piggybank.core.YearMonth
import app.piggybank.db.PiggyDatabase
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class IncomeLine(
    val id: Long,
    val name: String,
    val amountMinor: Long,
    val schedule: IncomeSchedule,
    val from: YearMonth,
    val to: YearMonth?,
    val monthsMask: Int,
)

/** Income, budgets, rates and refunds: everything the monthly review reads and the user sets up. */
class MonthService(
    private val db: PiggyDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val home: Currency get() = Currency.of(db.appQueries.getSetting("home_currency").executeAsOneOrNull() ?: "INR")

    // ---- Income (D7–D7d) ----

    fun incomes(): List<IncomeLine> = db.moneyQueries.selectAllIncome().executeAsList().map {
        IncomeLine(it.id, it.name, it.amount_minor, IncomeSchedule.valueOf(it.schedule),
            YearMonth.parse(it.from_month), it.to_month?.let(YearMonth::parse), it.months_mask.toInt())
    }

    fun incomeLinesFor(month: YearMonth): List<Pair<String, Long>> = incomes()
        .filter { Income(it.name, Money(it.amountMinor, home), it.schedule, it.from, it.to, it.monthsMask).appliesTo(month) }
        .map { it.name to it.amountMinor }

    fun addIncome(name: String, amountMinor: Long, schedule: IncomeSchedule, from: YearMonth, to: YearMonth?, mask: Int) =
        db.moneyQueries.insertIncome(name, amountMinor, schedule.name, from.toString(), to?.toString(), mask.toLong())

    fun updateIncome(line: IncomeLine) = db.moneyQueries.updateIncome(
        line.name, line.amountMinor, line.schedule.name, line.from.toString(), line.to?.toString(), line.monthsMask.toLong(), line.id,
    )

    /** D7c: a new amount from [month] on. Months before keep the old amount. */
    fun changeAmountFrom(line: IncomeLine, month: YearMonth, newAmountMinor: Long) = db.transaction {
        if (month <= line.from) {
            updateIncome(line.copy(amountMinor = newAmountMinor))
        } else {
            db.moneyQueries.endIncome(previous(month).toString(), line.id)
            addIncome(line.name, newAmountMinor, line.schedule, month, line.to, line.monthsMask)
        }
    }

    fun removeIncome(id: Long) = db.moneyQueries.deactivateIncome(id)

    // ---- Budgets ----

    /** The month's budgets, carried forward from the latest earlier month that has any. */
    fun budgetsFor(month: YearMonth): Map<Long, Long> {
        val source = db.moneyQueries.selectBudgetMonthOnOrBefore(month.toString()).executeAsOne().month ?: return emptyMap()
        return db.moneyQueries.selectBudgetForMonth(source).executeAsList().associate { it.category_id to it.amount_minor }
    }

    /** Saves budgets for [month]; later months without their own budgets inherit them. */
    fun saveBudgets(month: YearMonth, amounts: Map<Long, Long>) = db.transaction {
        db.moneyQueries.deleteBudgetMonth(month.toString())
        amounts.filterValues { it > 0 }.forEach { (cat, minor) -> db.moneyQueries.upsertBudget(month.toString(), cat, minor) }
    }

    /** Categories paid once a month in full (rent): left out of pace and allowance (H6). */
    fun fixedCategories(): Set<Long> =
        db.appQueries.getSetting(FIXED).executeAsOneOrNull()?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet()
            ?: db.categoryQueries.selectByName("Rent").executeAsOneOrNull()?.let { setOf(it.id) } ?: emptySet()

    fun setFixed(ids: Set<Long>) = db.appQueries.putSetting(FIXED, ids.joinToString(","))

    // ---- Exchange rates (M2, M3) ----

    fun rates(): Map<String, String> = db.moneyQueries.selectRates().executeAsList().associate { it.currency to it.rate_to_home }

    /** Currencies with payments still waiting for a home amount, and how many. */
    fun currenciesNeedingRate(): Map<String, Int> =
        db.txnQueries.selectNeedingRate().executeAsList().groupingBy { it.orig_currency }.eachCount()

    /** Saves "1 EUR = ₹90.20" and fills every waiting EUR payment with it. Never fetched online. */
    fun setRate(currency: String, rate: String): Boolean = db.transactionWithResult {
        val test = SmsIngestor.convert(Money(100, Currency.of(currency)), rate, home) ?: return@transactionWithResult false
        if (test.minor < 0) return@transactionWithResult false
        db.moneyQueries.upsertRate(currency, rate.trim(), now())
        db.txnQueries.selectNeedingRate().executeAsList().filter { it.orig_currency == currency }.forEach {
            val homeMoney = SmsIngestor.convert(Money(it.orig_amount_minor, Currency.of(currency)), rate, home) ?: return@forEach
            db.txnQueries.fillHomeAmount(homeMoney.minor, rate.trim(), it.id)
        }
        true
    }

    // ---- Refunds (B6, H10) ----

    /** Lowers a spend by the refunded amount, capped at the spend itself. Returns the amount applied. */
    fun markRefund(txnId: Long, refundMinor: Long): Long {
        val t = db.txnQueries.selectById(txnId).executeAsOneOrNull() ?: return 0
        val cap = t.home_amount_minor ?: t.orig_amount_minor
        val applied = refundMinor.coerceIn(0, cap)
        db.txnQueries.setRefund(applied, txnId)
        return applied
    }

    // ---- The report ----

    fun report(month: YearMonth, today: LocalDate, tz: TimeZone): MonthReport {
        val first = LocalDate(month.year, month.month, 1)
        val next = first.plus(DatePeriod(months = 1))
        val days = (next.toEpochDays() - first.toEpochDays())
        val rows = db.txnQueries.selectWithDetailsBetween(
            first.atStartOfDayIn(tz).toEpochMilliseconds(), next.atStartOfDayIn(tz).toEpochMilliseconds(),
        ).executeAsList().filter { it.duplicate_of == null }.map {
            SpendRow(
                day = Instant.fromEpochMilliseconds(it.occurred_at).toLocalDateTime(tz).dayOfMonth,
                kind = it.kind,
                categoryId = it.category_id,
                needWant = it.need_want,
                tagged = it.reviewed_at != null,
                homeMinor = it.home_amount_minor,
                refundMinor = it.refund_minor,
                origCurrency = it.orig_currency,
                origMinor = it.orig_amount_minor,
            )
        }
        val fixed = fixedCategories()
        val amounts = budgetsFor(month)
        val budgets = db.categoryQueries.selectByTopLevel("SPENDS").executeAsList().map {
            CategoryBudget(it.id, it.name, amounts[it.id] ?: 0, it.id in fixed)
        }
        val todayInMonth = if (today.year == month.year && today.monthNumber == month.month) today.dayOfMonth else null
        val isFuture = first > today
        return MonthMath.report(rows, budgets, incomeLinesFor(month), days, if (isFuture) 1 else todayInMonth, home.code)
    }

    companion object {
        const val FIXED = "fixed_categories"
        fun previous(m: YearMonth) = if (m.month == 1) YearMonth(m.year - 1, 12) else YearMonth(m.year, m.month - 1)
    }
}
