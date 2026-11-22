package app.piggybank.month

/** One payment as the monthly maths needs it. Amounts in home-currency minor units. */
data class SpendRow(
    val day: Int,
    val kind: String,
    val categoryId: Long?,
    val needWant: String?,
    /** Confirmed by the user or a rule. A suggestion alone doesn't count as tagged. */
    val tagged: Boolean,
    val homeMinor: Long?,
    val refundMinor: Long,
    val origCurrency: String,
    val origMinor: Long,
)

data class CategoryBudget(val categoryId: Long, val name: String, val budgetMinor: Long, val fixed: Boolean)

data class CategoryLine(
    val categoryId: Long,
    val name: String,
    val spent: Long,
    val budget: Long,
    val ratio: Double?,
    val status: String,
    val fixed: Boolean,
    /** For sorting: how far ahead of the month's pace it is. */
    val risk: Double,
)

data class MonthReport(
    val daysInMonth: Int,
    /** Days of the month that have started (today counts). Equals [daysInMonth] for a past month. */
    val daysSoFar: Int,
    val pace: Double,
    val incomeLines: List<Pair<String, Long>>,
    val income: Long,
    val needs: Long,
    val wants: Long,
    val untagged: Long,
    val invested: Long,
    val spends: Long,
    /** Null when no income is set up (H2). Negative means overspent (H4). */
    val savings: Long?,
    val savingsPct: Int?,
    val stillInBank: Long?,
    val budgetTotal: Long,
    val fixedBudget: Long,
    val variableSpent: Long,
    /** H7: month-end spend if the rest of the month goes like so far. Null for past months. */
    val projection: Long?,
    /** G3: what can be spent today and still stay inside the variable budget. */
    val allowanceToday: Long?,
    val spentToday: Long,
    val categories: List<CategoryLine>,
    /** Cumulative spends at the end of each day, index 0 = day 1 (only up to [daysSoFar]). */
    val cumulative: List<Long>,
    val selfTransfers: Long,
    val cardBills: Long,
    val walletTopUps: Long,
    /** M8: original amounts by currency for foreign spends. */
    val foreign: Map<String, Long>,
    val needingRate: Int,
    val suggestion: Pair<String, Pair<String, Long>>?,
)

/**
 * Pure monthly maths (TEST_CASES G and H). No database, no clock: the caller passes the month's
 * rows, budgets, income lines and which day it is.
 */
object MonthMath {

    fun report(
        rows: List<SpendRow>,
        budgets: List<CategoryBudget>,
        incomeLines: List<Pair<String, Long>>,
        daysInMonth: Int,
        /** 1-based day of the month today, or null if the month is over (or [daysInMonth] then). */
        today: Int?,
        homeCurrency: String,
    ): MonthReport {
        val daysSoFar = today ?: daysInMonth
        val pace = daysSoFar.toDouble() / daysInMonth // H5, H8: real month length

        fun value(r: SpendRow) = ((r.homeMinor ?: 0) - r.refundMinor).coerceAtLeast(0) // H10: never below 0
        val spends = rows.filter { it.kind == "SPEND" && it.homeMinor != null }
        val needs = spends.filter { it.tagged && it.needWant == "NEED" }.sumOf(::value)
        val wants = spends.filter { it.tagged && it.needWant == "WANT" }.sumOf(::value)
        val total = spends.sumOf(::value)
        val untagged = total - needs - wants
        val invested = rows.filter { it.kind == "INVESTMENT" }.sumOf(::value)

        val income = incomeLines.sumOf { it.second }
        val savings = if (income == 0L) null else income - total // Savings = income − spends; untagged spends still count
        val savingsPct = savings?.let { kotlin.math.round(it * 100.0 / income).toInt() }

        val byCategory = spends.filter { it.tagged && it.categoryId != null }.groupBy { it.categoryId!! }
            .mapValues { (_, v) -> v.sumOf(::value) }
        val fixedIds = budgets.filter { it.fixed }.map { it.categoryId }.toSet()
        val fixedSpent = spends.filter { it.tagged && it.categoryId in fixedIds }.sumOf(::value)
        val variableSpent = total - fixedSpent
        val budgetTotal = budgets.sumOf { it.budgetMinor }
        val fixedBudget = budgets.filter { it.fixed }.sumOf { it.budgetMinor }
        val variableBudget = budgetTotal - fixedBudget

        val categories = budgets.filter { it.budgetMinor > 0 || (byCategory[it.categoryId] ?: 0) > 0 }.map { b ->
            val spent = byCategory[b.categoryId] ?: 0
            val ratio = if (b.budgetMinor > 0) spent.toDouble() / b.budgetMinor else null
            val status = when {
                b.fixed -> if (spent >= b.budgetMinor && b.budgetMinor > 0) "Paid · fixed" else "Fixed · not paid yet" // H6
                ratio == null -> "No budget"
                ratio > 1.0 -> "Over budget"
                ratio > pace + 0.05 -> "Over pace"
                ratio < pace - 0.25 -> "Way under"
                else -> "On pace"
            }
            CategoryLine(b.categoryId, b.name, spent, b.budgetMinor, ratio, status, b.fixed,
                risk = if (b.fixed) -10.0 else (ratio ?: 2.0) - pace)
        }.sortedByDescending { it.risk }

        val perDay = LongArray(daysInMonth)
        spends.forEach { r -> if (r.day in 1..daysInMonth) perDay[r.day - 1] += value(r) }
        val cumulative = perDay.take(daysSoFar).runningReduce { a, b -> a + b }

        val spentToday = if (today != null) perDay[today - 1] else 0
        val variableToday = if (today != null) spends.filter { it.day == today && !(it.tagged && it.categoryId in fixedIds) }.sumOf(::value) else 0
        val projection = if (today != null) total + variableSpent * (daysInMonth - daysSoFar) / daysSoFar else null
        // G3: (variable budget left at the start of today) ÷ (days left including today).
        val allowance = if (today != null && variableBudget > 0) {
            (variableBudget - (variableSpent - variableToday)) / (daysInMonth - today + 1)
        } else null

        val foreign = spends.filter { it.origCurrency != homeCurrency }.groupBy { it.origCurrency }
            .mapValues { (_, v) -> v.sumOf { it.origMinor } }

        return MonthReport(
            daysInMonth = daysInMonth,
            daysSoFar = daysSoFar,
            pace = pace,
            incomeLines = incomeLines,
            income = income,
            needs = needs,
            wants = wants,
            untagged = untagged,
            invested = invested,
            spends = total,
            savings = savings,
            savingsPct = savingsPct,
            stillInBank = savings?.let { it - invested },
            budgetTotal = budgetTotal,
            fixedBudget = fixedBudget,
            variableSpent = variableSpent,
            projection = projection,
            allowanceToday = allowance,
            spentToday = spentToday,
            categories = categories,
            cumulative = cumulative,
            selfTransfers = rows.filter { it.kind == "SELF_TRANSFER" }.sumOf { it.homeMinor ?: 0 },
            cardBills = rows.filter { it.kind == "CC_BILL" }.sumOf { it.homeMinor ?: 0 },
            walletTopUps = rows.filter { it.kind == "WALLET_TOPUP" }.sumOf { it.homeMinor ?: 0 },
            foreign = foreign,
            needingRate = rows.count { it.homeMinor == null },
            suggestion = suggestMove(categories, pace),
        )
    }

    /**
     * "Move ₹X from A to B": the category heading furthest over budget at this pace, covered by the
     * category with the most room left. Returns (to, (from, amount)).
     */
    private fun suggestMove(lines: List<CategoryLine>, pace: Double): Pair<String, Pair<String, Long>>? {
        if (pace <= 0) return null
        val projected = { l: CategoryLine -> (l.spent / pace).toLong() }
        val over = lines.filter { !it.fixed && it.budget > 0 && projected(it) > it.budget }
            .maxByOrNull { projected(it) - it.budget } ?: return null
        val under = lines.filter { !it.fixed && it.budget > 0 && it.status == "Way under" }
            .maxByOrNull { it.budget - projected(it) } ?: return null
        val need = projected(over) - over.budget
        val room = under.budget - projected(under)
        val amount = minOf(need, room).let { (it / 100_00) * 100_00 } // round down to ₹100
        if (amount <= 0) return null
        return over.name to (under.name to amount)
    }
}
