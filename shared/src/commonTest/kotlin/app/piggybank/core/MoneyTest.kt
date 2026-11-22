package app.piggybank.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MoneyTest {
    private val inr = Currency.INR

    @Test fun parsesIndianFormats() { // B2, B3
        assertEquals(2_000_000L, Money.parse("20000.00", inr)!!.minor)
        assertEquals(2_000_000L, Money.parse("20,000", inr)!!.minor)
        assertEquals(2_000_000L, Money.parse("20,000/-", inr)!!.minor)
        assertEquals(15_000_000L, Money.parse("1,50,000.00", inr)!!.minor)
        assertEquals(116_450L, Money.parse("1,164.50", inr)!!.minor)
        assertEquals(50L, Money.parse("0.5", inr)!!.minor)
    }

    @Test fun rejectsGarbage() {
        assertNull(Money.parse("12a", inr))
        assertNull(Money.parse("", inr))
        assertNull(Money.parse("1.234", inr)) // three decimals is not valid rupees
    }

    @Test fun respectsCurrencyDecimals() { // M4
        val jpy = Currency.of("JPY")
        val kwd = Currency.of("KWD")
        assertEquals(1200L, Money.parse("1,200", jpy)!!.minor)
        assertEquals(1200L, Money.parse("1200.00", jpy)!!.minor)
        assertEquals(3500L, Money.parse("3.500", kwd)!!.minor)
        assertEquals("¥1,200", Money(1200, jpy).format())
        assertEquals("KWD 3.500", Money(3500, kwd).format())
    }

    @Test fun formatsWithIndianGrouping() {
        assertEquals("₹1,50,000", Money(15_000_000, inr).format(compact = true))
        assertEquals("₹1,50,000.00", Money(15_000_000, inr).format())
        assertEquals("₹2,113.40", Money(211_340, inr).format())
        assertEquals("₹999", Money(99_900, inr).format(compact = true))
        assertEquals("₹1,23,45,678", Money(1_234_567_800, inr).format(compact = true))
        assertEquals("$1,234,567.89", Money(123_456_789, Currency.of("USD")).format())
        assertEquals("−₹4,200", Money(-420_000, inr).format(compact = true))
    }

    @Test fun refusesToMixCurrencies() {
        assertFailsWith<IllegalArgumentException> { Money(100, inr) + Money(100, Currency.of("USD")) }
    }
}
