package app.piggybank.month

import app.piggybank.core.Income
import app.piggybank.core.IncomeSchedule
import app.piggybank.core.YearMonth
import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonthServiceTest {
    private lateinit var db: PiggyDatabase
    private lateinit var svc: MonthService

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
        svc = MonthService(db) { 0 }
    }

    private fun ym(s: String) = YearMonth.parse(s)

    @Test fun d7cRaiseFromAprilKeepsPastMonths() {
        svc.addIncome("Salary", 150_000_00, IncomeSchedule.MONTHLY, ym("2026-04"), ym("2027-03"), Income.ALL_MONTHS)
        svc.changeAmountFrom(svc.incomes().single(), ym("2026-10"), 170_000_00)
        assertEquals(listOf("Salary" to 150_000_00L), svc.incomeLinesFor(ym("2026-09")))
        assertEquals(listOf("Salary" to 170_000_00L), svc.incomeLinesFor(ym("2026-10")))
        assertEquals(listOf("Salary" to 170_000_00L), svc.incomeLinesFor(ym("2027-03")))
        assertEquals(emptyList(), svc.incomeLinesFor(ym("2027-04")))
    }

    @Test fun d7bOneOffAndD7dSeasonal() {
        svc.addIncome("Bonus", 60_000_00, IncomeSchedule.ONE_OFF, ym("2027-03"), null, Income.ALL_MONTHS)
        svc.addIncome("Tuition", 5_000_00, IncomeSchedule.MONTHLY, ym("2026-01"), null, 0b000011100000) // Jun, Jul, Aug
        assertEquals(1, svc.incomeLinesFor(ym("2027-03")).size)
        assertEquals(listOf("Tuition" to 5_000_00L), svc.incomeLinesFor(ym("2026-07")))
        assertEquals(emptyList(), svc.incomeLinesFor(ym("2026-09")))
    }

    @Test fun budgetsCarryForward() {
        val groc = db.categoryQueries.selectByName("Groceries").executeAsOne().id
        svc.saveBudgets(ym("2026-10"), mapOf(groc to 12_000_00))
        assertEquals(12_000_00, svc.budgetsFor(ym("2026-12"))[groc])
        svc.saveBudgets(ym("2026-12"), mapOf(groc to 15_000_00))
        assertEquals(12_000_00, svc.budgetsFor(ym("2026-11"))[groc])
        assertEquals(15_000_00, svc.budgetsFor(ym("2027-01"))[groc])
        assertNull(svc.budgetsFor(ym("2026-09"))[groc])
    }

    @Test fun m3RateFillsWaitingPayments() {
        db.txnQueries.insert(null, 0, "SMS", "EUR", 40_00, null, null, "USER_RATE", null, "ZARA", null, null, "SPEND")
        assertEquals(mapOf("EUR" to 1), svc.currenciesNeedingRate())
        svc.setRate("EUR", "90.20")
        assertEquals(3_608_00, db.txnQueries.selectById(1).executeAsOne().home_amount_minor)
        assertEquals(emptyMap(), svc.currenciesNeedingRate())
    }

    @Test fun b6RefundIsCappedAtTheSpend() {
        db.txnQueries.insert(null, 0, "SMS", "INR", 612_00, 612_00, null, "SAME", null, "SWIGGY", null, null, "SPEND")
        assertEquals(612_00, svc.markRefund(1, 900_00))
        assertEquals(612_00, db.txnQueries.selectById(1).executeAsOne().refund_minor)
    }

    @Test fun rentIsFixedByDefault() {
        val rent = db.categoryQueries.selectByName("Rent").executeAsOne().id
        assertEquals(setOf(rent), svc.fixedCategories())
    }
}
