package app.piggybank.month

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TEST_CASES G and H, with fixed dates. Amounts in paise. */
class MonthMathTest {
    private val RENT = 1L; private val FOOD = 2L; private val SHOP = 3L; private val GROC = 4L
    private fun r(day: Int, rupees: Long, kind: String = "SPEND", cat: Long? = null, nw: String? = null, tagged: Boolean = true, refund: Long = 0) =
        SpendRow(day, kind, cat, nw, tagged, rupees * 100, refund * 100, "INR", rupees * 100)

    private fun report(rows: List<SpendRow>, budgets: List<CategoryBudget> = emptyList(), income: List<Pair<String, Long>> = emptyList(),
                       days: Int = 30, today: Int? = 21) = MonthMath.report(rows, budgets, income, days, today, "INR")

    @Test fun h1Savings() {
        val m = report(
            listOf(r(3, 38_200, nw = "NEED"), r(5, 20_400, nw = "WANT"), r(7, 25_000, kind = "INVESTMENT")),
            income = listOf("Salary" to 150_000_00L),
        )
        assertEquals(91_400_00, m.savings)
        assertEquals(61, m.savingsPct) // 91,400 / 1,50,000 = 60.9%
        assertEquals(25_000_00, m.invested)
        assertEquals(66_400_00, m.stillInBank)
    }

    @Test fun h2NoIncomeMeansNoSavingsNumber() {
        assertNull(report(listOf(r(3, 500, nw = "NEED"))).savings)
    }

    @Test fun h3TwoIncomeLines() {
        val m = report(emptyList(), income = listOf("Salary" to 150_000_00L, "Rent received" to 18_000_00L))
        assertEquals(168_000_00, m.income)
        assertEquals(2, m.incomeLines.size)
    }

    @Test fun h4Overspent() {
        val m = report(listOf(r(3, 154_200, nw = "WANT")), income = listOf("Salary" to 150_000_00L))
        assertEquals(-4_200_00, m.savings)
    }

    @Test fun h5PaceMarkers() {
        val budgets = listOf(CategoryBudget(FOOD, "Food delivery", 10_000_00, false), CategoryBudget(SHOP, "Shopping", 10_000_00, false))
        val m = report(listOf(r(4, 8_200, cat = FOOD, nw = "WANT"), r(5, 3_400, cat = SHOP, nw = "WANT")), budgets)
        assertEquals(0.70, m.pace, 1e-9)
        assertEquals("Over pace", m.categories.first { it.name == "Food delivery" }.status)
        assertEquals("Way under", m.categories.first { it.name == "Shopping" }.status)
        assertEquals("Food delivery", m.categories.first().name) // riskiest first
    }

    @Test fun h6FixedRentIsPaidNotPaced() {
        val budgets = listOf(CategoryBudget(RENT, "Rent", 25_000_00, true))
        val m = report(listOf(r(1, 25_000, cat = RENT, nw = "NEED")), budgets)
        assertEquals("Paid · fixed", m.categories.single().status)
        assertEquals(0, m.variableSpent)
    }

    @Test fun h7Projection() {
        // Day 10 of 30, ₹25,000 rent (fixed) + ₹10,000 variable so far → 35,000 + 10,000/10 × 20 = 55,000.
        val budgets = listOf(CategoryBudget(RENT, "Rent", 25_000_00, true))
        val m = report(listOf(r(1, 25_000, cat = RENT, nw = "NEED"), r(5, 10_000, cat = FOOD, nw = "WANT")), budgets, today = 10)
        assertEquals(55_000_00, m.projection)
    }

    @Test fun h8RealMonthLength() {
        assertEquals(0.5, report(emptyList(), days = 28, today = 14).pace, 1e-9)
        assertEquals(10, report(emptyList(), days = 29, today = 10).daysSoFar)
    }

    @Test fun h9PastMonthIsFinal() {
        val m = report(listOf(r(30, 100, nw = "WANT")), days = 31, today = null)
        assertEquals(1.0, m.pace, 1e-9)
        assertNull(m.projection)
        assertEquals(31, m.daysSoFar)
    }

    @Test fun h10RefundLowersSpendNeverBelowZero() {
        val m = report(listOf(r(3, 612, cat = FOOD, nw = "WANT", refund = 612), r(4, 1_000, cat = FOOD, nw = "WANT", refund = 300)))
        assertEquals(700_00, m.spends)
    }

    @Test fun g1G2TodayNumbers() {
        val m = report(listOf(
            r(21, 1_650, nw = "NEED"), r(21, 932, nw = "WANT"), r(21, 258, tagged = false),
            r(21, 5_000, kind = "INVESTMENT"), r(21, 20_000, kind = "SELF_TRANSFER"), r(21, 18_200, kind = "CC_BILL"),
        ))
        assertEquals(2_840_00, m.spentToday)
        assertEquals(258_00, m.untagged)
        assertEquals(5_000_00, m.invested)
        assertEquals(20_000_00, m.selfTransfers)
    }

    @Test fun g3Allowance() {
        // ₹90,000 budget, ₹25,000 fixed rent → ₹65,000 variable. ₹30,760 variable spent before today, ₹2,840 today,
        // 10 days left including today: (65,000 − 30,760) ÷ 10 = ₹3,424.
        val budgets = listOf(CategoryBudget(RENT, "Rent", 25_000_00, true), CategoryBudget(GROC, "Groceries", 65_000_00, false))
        val m = report(listOf(r(1, 25_000, cat = RENT, nw = "NEED"), r(10, 30_760, cat = GROC, nw = "NEED"), r(21, 2_840, cat = GROC, nw = "NEED")), budgets)
        assertEquals(3_424_00, m.allowanceToday)
    }

    @Test fun g4OverAllowanceStillWorks() {
        val budgets = listOf(CategoryBudget(GROC, "Groceries", 10_000_00, false))
        val m = report(listOf(r(21, 9_000, cat = GROC, nw = "NEED")), budgets)
        assertTrue(m.spentToday > (m.allowanceToday ?: 0))
    }

    @Test fun m8ForeignTotals() {
        val m = report(listOf(SpendRow(5, "SPEND", null, null, false, 2_113_40, 0, "USD", 25_00), SpendRow(6, "SPEND", null, null, false, null, 0, "EUR", 40_00)))
        assertEquals(mapOf("USD" to 25_00L), m.foreign) // the EUR one has no ₹ amount yet and stays out of totals
        assertEquals(1, m.needingRate)
        assertEquals(2_113_40, m.spends)
    }

    @Test fun budgetMoveSuggestion() {
        val budgets = listOf(CategoryBudget(FOOD, "Food delivery", 10_000_00, false), CategoryBudget(SHOP, "Shopping", 20_000_00, false))
        val m = report(listOf(r(4, 10_000, cat = FOOD, nw = "WANT"), r(5, 2_000, cat = SHOP, nw = "WANT")), budgets, days = 30, today = 15)
        // Food is heading to ₹20,000 (₹10,000 over); Shopping to ₹4,000 (₹16,000 spare) → move ₹10,000.
        assertEquals("Food delivery" to ("Shopping" to 10_000_00L), m.suggestion)
    }
}
