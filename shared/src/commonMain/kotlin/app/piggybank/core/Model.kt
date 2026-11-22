package app.piggybank.core

/** What a payment is. Only [SPEND] counts toward spends; the last three are hidden and never counted. */
enum class TxnKind { SPEND, INVESTMENT, SELF_TRANSFER, CC_BILL, WALLET_TOPUP;
    val isCounted: Boolean get() = this == SPEND
    val isHidden: Boolean get() = this == SELF_TRANSFER || this == CC_BILL || this == WALLET_TOPUP
}

enum class TopLevel { SPENDS, INVESTMENTS, EXCLUDED }

enum class NeedWant { NEED, WANT }

enum class TagSource { RULE, USER, GUESS }

enum class TemplateVerdict { WHITELIST, BLACKLIST, PENDING }

enum class SmsStatus { PARSED, CANDIDATE, IGNORED, USER_PICKED, NOT_MONEY }

enum class DateSource { SMS, ARRIVAL, USER }

enum class FxSource { SAME, SMS, USER_RATE }

enum class IncomeSchedule { MONTHLY, ONE_OFF }

/** Months are calendar months, written 'YYYY-MM'. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init { require(month in 1..12) }
    override fun compareTo(other: YearMonth) = compareValuesBy(this, other, { it.year }, { it.month })
    override fun toString() = "$year-${month.toString().padStart(2, '0')}"
    fun next() = if (month == 12) YearMonth(year + 1, 1) else YearMonth(year, month + 1)

    companion object {
        fun parse(s: String): YearMonth {
            val (y, m) = s.split('-')
            return YearMonth(y.toInt(), m.toInt())
        }
    }
}

/** An income line set up by the user (salary, rent received, bonus…). */
data class Income(
    val name: String,
    val amount: Money,
    val schedule: IncomeSchedule,
    val from: YearMonth,
    val to: YearMonth?,
    /** Bit 0 = January … bit 11 = December. */
    val monthsMask: Int = ALL_MONTHS,
) {
    fun appliesTo(month: YearMonth): Boolean = when (schedule) {
        IncomeSchedule.ONE_OFF -> month == from
        IncomeSchedule.MONTHLY ->
            month >= from && (to == null || month <= to) && (monthsMask shr (month.month - 1)) and 1 == 1
    }

    companion object { const val ALL_MONTHS = 0xFFF }
}

/** Total income for a month across every income line that applies. Test cases D7–D7d. */
fun incomeFor(month: YearMonth, incomes: List<Income>, home: Currency): Money =
    incomes.filter { it.appliesTo(month) }.fold(Money.zero(home)) { acc, i -> acc + i.amount }

/**
 * Savings = income − (needs + wants). Investments are part of savings, not subtracted.
 * Returns null when no income is set up for the month (shown as "Add your income"). Test cases H1–H4.
 */
fun savings(income: Money, needs: Money, wants: Money): Money? =
    if (income.minor == 0L) null else income - needs - wants
