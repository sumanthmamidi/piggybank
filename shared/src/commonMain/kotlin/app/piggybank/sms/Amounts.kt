package app.piggybank.sms

import app.piggybank.core.Currency
import app.piggybank.core.Money

/** One amount written in an SMS, with the currency it was written in. */
data class AmountMention(
    val money: Money,
    val range: IntRange,
    /** Preceded by "Avl Bal", "limit", "outstanding", "due": never the payment amount (B4). */
    val isBalance: Boolean,
    /** "INR equivalent 2,113.40": the home value of a foreign payment (M1). */
    val isEquivalent: Boolean,
    /** A bare "$": assumed USD, but the user should confirm (M5). */
    val ambiguousCurrency: Boolean,
)

/** Finds every "currency + number" in an SMS. Any sender, any bank: this is the money filter (A2, A4). */
object Amounts {
    private const val CURRENCY =
        """INR|Rs\.?|₹|US\$|USD|S\$|SGD|A\$|AUD|C\$|CAD|\$|EUR|€|GBP|£|AED|JPY|¥|KWD|BHD|OMR|SAR|QAR|THB|฿|CHF|HKD|NZD|MYR"""
    private const val FILLER = """(?:\s*(equivalent|equiv\.?|eqv\.?|amount|amt)\.?\s*(?:of|is)?\s*:?)?"""
    private const val NUMBER = """(\d[\d,]*(?:\.\d+)?)"""

    /** Group 1 = currency token, 2 = filler word, 3 = number. */
    val pattern = Regex("""(?<![A-Za-z])($CURRENCY)$FILLER\s*[.:]?\s*$NUMBER(?:\s*/-)?""", RegexOption.IGNORE_CASE)

    private val balanceBefore =
        Regex("""(?:\bbal|balance|\blimit|\blmt|outstanding|\bdue)\b[^0-9]{0,15}$""", RegexOption.IGNORE_CASE)
    private val faceValueBefore = Regex("""-(?:EQ\s*)?$""", RegexOption.IGNORE_CASE)
    private val equivalentBefore = Regex("""(?:equivalent|equiv|approx|in inr)[^0-9]{0,6}$""", RegexOption.IGNORE_CASE)

    fun find(body: String): List<AmountMention> = pattern.findAll(body).mapNotNull { m ->
        val (currency, ambiguous) = currencyOf(m.groupValues[1])
        val money = Money.parse(m.groupValues[3].trimEnd(','), currency) ?: return@mapNotNull null
        // "Trxn. of USD.00": a blank amount, not a payment of zero.
        if (money.minor == 0L) return@mapNotNull null
        val before = body.substring(maxOf(0, m.range.first - 30), m.range.first)
        val filler = m.groupValues[2].lowercase()
        AmountMention(
            money = money,
            range = m.range,
            // "88-GMDC-EQ RS2/-", "INDIA-RS 2/-": a share's face value, not money that moved.
            isBalance = balanceBefore.containsMatchIn(before) || faceValueBefore.containsMatchIn(before),
            isEquivalent = filler.startsWith("eq") || equivalentBefore.containsMatchIn(before),
            ambiguousCurrency = ambiguous,
        )
    }.toList()

    /** Maps a token to its currency; the flag is true for a bare "$". */
    fun currencyOf(token: String): Pair<Currency, Boolean> = when (token.uppercase().trimEnd('.')) {
        "INR", "RS", "₹" -> Currency.INR to false
        "US$", "USD" -> Currency.of("USD") to false
        "$" -> Currency.of("USD") to true
        "EUR", "€" -> Currency.of("EUR") to false
        "GBP", "£" -> Currency.of("GBP") to false
        "JPY", "¥" -> Currency.of("JPY") to false
        "SGD", "S$" -> Currency.of("SGD") to false
        "AUD", "A$" -> Currency.of("AUD") to false
        "CAD", "C$" -> Currency.of("CAD") to false
        "THB", "฿" -> Currency.of("THB") to false
        else -> Currency.of(token.uppercase()) to false
    }
}
