package app.piggybank.core

/**
 * A currency and how many decimal places its smallest unit has (ISO 4217 exponent).
 * Unknown codes default to 2 decimals, which is right for almost every currency.
 */
data class Currency(val code: String, val decimals: Int, val symbol: String) {
    companion object {
        private val known = listOf(
            Currency("INR", 2, "₹"),
            Currency("USD", 2, "$"),
            Currency("EUR", 2, "€"),
            Currency("GBP", 2, "£"),
            Currency("AED", 2, "AED "),
            Currency("SGD", 2, "S$"),
            Currency("AUD", 2, "A$"),
            Currency("CAD", 2, "C$"),
            Currency("THB", 2, "฿"),
            Currency("JPY", 0, "¥"),
            Currency("KRW", 0, "₩"),
            Currency("VND", 0, "₫"),
            Currency("KWD", 3, "KWD "),
            Currency("BHD", 3, "BHD "),
            Currency("OMR", 3, "OMR "),
        ).associateBy { it.code }

        fun of(code: String): Currency {
            val upper = code.uppercase()
            return known[upper] ?: Currency(upper, 2, "$upper ")
        }

        val INR = of("INR")
    }
}

/**
 * An amount stored as a whole number of the currency's smallest unit (paise, cents, yen).
 * Never use floating point for money.
 */
data class Money(val minor: Long, val currency: Currency) : Comparable<Money> {

    operator fun plus(other: Money): Money = same(other).let { Money(minor + other.minor, currency) }
    operator fun minus(other: Money): Money = same(other).let { Money(minor - other.minor, currency) }
    override fun compareTo(other: Money): Int = same(other).let { minor.compareTo(other.minor) }

    val isNegative: Boolean get() = minor < 0

    private fun same(other: Money) {
        require(currency.code == other.currency.code) {
            "Can't mix ${currency.code} and ${other.currency.code}; convert to home currency first"
        }
    }

    /** Plain decimal string, e.g. "1234.50", "1200" (JPY), "3.500" (KWD). */
    fun toPlainString(): String {
        val d = currency.decimals
        val sign = if (minor < 0) "-" else ""
        val abs = kotlin.math.abs(minor)
        if (d == 0) return "$sign$abs"
        val unit = pow10(d)
        val whole = abs / unit
        val frac = (abs % unit).toString().padStart(d, '0')
        return "$sign$whole.$frac"
    }

    /**
     * Display string. INR uses Indian grouping (₹1,50,000); others use groups of three.
     * Trailing ".00" is dropped when [compact] is true.
     */
    fun format(compact: Boolean = false): String {
        val d = currency.decimals
        val abs = kotlin.math.abs(minor)
        val unit = pow10(d)
        val whole = abs / unit
        val frac = if (d == 0) "" else (abs % unit).toString().padStart(d, '0')
        val grouped = if (currency.code == "INR") groupIndian(whole) else groupThousands(whole)
        val fracPart = when {
            d == 0 -> ""
            compact && frac.all { it == '0' } -> ""
            else -> ".$frac"
        }
        val sign = if (minor < 0) "−" else ""
        return "$sign${currency.symbol}$grouped$fracPart"
    }

    companion object {
        fun zero(currency: Currency) = Money(0, currency)

        /** Parses "20000", "20,000.00", "1,50,000.5" for the given currency. Rejects more decimals than allowed. */
        fun parse(text: String, currency: Currency): Money? {
            val cleaned = text.trim().replace(",", "").removeSuffix("/-")
            if (!Regex("""\d+(\.\d+)?""").matches(cleaned)) return null
            val parts = cleaned.split('.')
            val whole = parts[0].toLongOrNull() ?: return null
            val fracText = parts.getOrNull(1) ?: ""
            if (fracText.length > currency.decimals) {
                // Allow trailing zeros beyond the precision ("1200.00" for JPY), reject real extra digits.
                if (fracText.substring(currency.decimals).any { it != '0' }) return null
            }
            val frac = fracText.take(currency.decimals).padEnd(currency.decimals, '0')
            val minor = whole * pow10(currency.decimals) + (if (frac.isEmpty()) 0 else frac.toLong())
            return Money(minor, currency)
        }

        internal fun pow10(n: Int): Long {
            var r = 1L
            repeat(n) { r *= 10 }
            return r
        }

        internal fun groupThousands(n: Long): String =
            n.toString().reversed().chunked(3).joinToString(",").reversed()

        /** 150000 → "1,50,000": last three digits, then groups of two. */
        internal fun groupIndian(n: Long): String {
            val s = n.toString()
            if (s.length <= 3) return s
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3)
            val restGrouped = rest.reversed().chunked(2).joinToString(",").reversed()
            return "$restGrouped,$last3"
        }
    }
}
