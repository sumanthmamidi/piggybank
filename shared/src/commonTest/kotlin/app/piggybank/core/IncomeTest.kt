package app.piggybank.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IncomeTest {
    private val inr = Currency.INR
    private fun rupees(n: Long) = Money(n * 100, inr)
    private fun ym(s: String) = YearMonth.parse(s)

    private val salary = Income("Salary", rupees(150_000), IncomeSchedule.MONTHLY, ym("2026-04"), ym("2027-03"))

    @Test fun salaryAppliesOnlyInsideItsRange() { // D7
        assertEquals(rupees(150_000), incomeFor(ym("2026-11"), listOf(salary), inr))
        assertEquals(rupees(0), incomeFor(ym("2026-03"), listOf(salary), inr))
        assertEquals(rupees(0), incomeFor(ym("2027-04"), listOf(salary), inr))
    }

    @Test fun recurringIncomesAddUp() { // D7a, H3
        val rent = Income("Rent received", rupees(18_000), IncomeSchedule.MONTHLY, ym("2026-01"), null)
        assertEquals(rupees(168_000), incomeFor(ym("2026-11"), listOf(salary, rent), inr))
    }

    @Test fun oneOffCountsInOneMonthOnly() { // D7b
        val bonus = Income("Bonus", rupees(60_000), IncomeSchedule.ONE_OFF, ym("2027-03"), null)
        assertEquals(rupees(210_000), incomeFor(ym("2027-03"), listOf(salary, bonus), inr))
        assertEquals(rupees(150_000), incomeFor(ym("2027-02"), listOf(salary, bonus), inr))
    }

    @Test fun hikeFromAprilLeavesEarlierMonths() { // D7c
        val old = salary.copy(to = ym("2027-03"))
        val hiked = Income("Salary", rupees(170_000), IncomeSchedule.MONTHLY, ym("2027-04"), null)
        assertEquals(rupees(150_000), incomeFor(ym("2027-03"), listOf(old, hiked), inr))
        assertEquals(rupees(170_000), incomeFor(ym("2027-04"), listOf(old, hiked), inr))
    }

    @Test fun pickedMonthsOnly() { // D7d: June, July, August
        val mask = (1 shl 5) or (1 shl 6) or (1 shl 7)
        val seasonal = Income("Seasonal", rupees(10_000), IncomeSchedule.MONTHLY, ym("2026-01"), null, mask)
        assertEquals(rupees(10_000), incomeFor(ym("2026-07"), listOf(seasonal), inr))
        assertEquals(rupees(0), incomeFor(ym("2026-09"), listOf(seasonal), inr))
    }

    @Test fun savingsMaths() { // H1, H2, H4
        assertEquals(rupees(91_400), savings(rupees(150_000), rupees(38_200), rupees(20_400)))
        assertNull(savings(rupees(0), rupees(1_000), rupees(0)))
        assertEquals(rupees(-4_200), savings(rupees(50_000), rupees(40_000), rupees(14_200)))
    }

    @Test fun yearMonthRollsOver() {
        assertEquals(ym("2027-01"), ym("2026-12").next())
        assertEquals("2026-04", ym("2026-04").toString())
    }
}
