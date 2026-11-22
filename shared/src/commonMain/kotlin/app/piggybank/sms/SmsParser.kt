package app.piggybank.sms

import app.piggybank.core.Currency
import app.piggybank.core.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

enum class Direction { DEBIT, CREDIT, UNKNOWN }

/** What the generic parser could pull out of one SMS. Fields it couldn't find are null. */
data class ParsedSms(
    val direction: Direction,
    /** The payment amount as written (original currency). Null when only balances were found. */
    val amount: Money?,
    /** The home-currency value written in the SMS for a foreign payment ("INR equivalent …"). */
    val homeAmount: Money?,
    val ambiguousCurrency: Boolean,
    val accountLast4: String?,
    val accountIsCard: Boolean,
    /** The account the money went to, when the SMS names one ("to A/c XX9910", "a/c XX319 credited"). */
    val toAccountLast4: String? = null,
    val vpa: String?,
    val merchant: String?,
    val date: LocalDate?,
    val time: LocalTime?,
) {
    /** A payment we can store as a transaction without asking. */
    val isPayment: Boolean get() = direction == Direction.DEBIT && amount != null
}

/**
 * The generic parser: works on any bank's wording without a per-bank list.
 * Learned templates (field_map) refine it later; this is the fallback for every new format.
 */
object SmsParser {
    private val I = RegexOption.IGNORE_CASE

    /** OTPs, reminders, future debits, collect requests, failures: never a completed payment. */
    private val notATxn = Regex(
        """(?<!without )\botp\b|one[- ]time password|verification code|will be (?:auto[- ]?)?(?:debited|charged|deducted)|""" +
            """to be debited|is due|\bdue (?:on|by|date)|requested|collect request|declined|failed|unsuccessful|""" +
            """not successful|could not|reminder|pay now|will be confirmed|subject to realisation""",
        I,
    )
    private val debitWords = Regex(
        """\b(?:debited|debit(?!\s*card)|spent|paid|sent|withdrawn|withdrawal|purchased?|charged|deducted|""" +
            """transferred|used|executed|txn amt|(?:txn|transfer|rtgs|neft|imps)\s+initiated|dr\.?)(?![a-z])""",
        I,
    )
    private val creditWords = Regex(
        """\b(?:credited|credit(?!\s*(?:card|limit|lmt))|received|deposited|refund(?:ed)?|reversed|reversal|cr\.?)(?![a-z])""",
        I,
    )

    /** Money coming back is always money in, whatever verb comes first ("initiated a refund"). */
    private val refundWords = Regex("""\b(?:refund(?:ed)?|reversed|reversal)\b""", I)

    private val accountWithMask = Regex(
        """(?<![A-Za-z])(a/?c|acct|account|card|ac)\b[^0-9\n]{0,20}?[xX*•]+\s*(\d{3,6})""",
        I,
    )
    private val accountEnding = Regex("""(?:(card|a/?c|account)\s+)?ending(?:\s+(?:with|in))?\s*:?\s*(\d{4})""", I)
    /** "Card 4444", "A/C No 1111", "CC 4444", "credit card (6145)": last-4 written without a mask. */
    private val accountPlain = Regex("""(?<![A-Za-z])(card|a/?c|acct|account|cc)\b\.?\s*(?:no\.?|number)?\s*[:(]?\s*(\d{4})(?!\d)""", I)
    private val bareMask = Regex("""(?<![A-Za-z0-9])[xX*]{2,}(\d{3,6})\b""")

    val vpaPattern = Regex("""(?<![\w.\-])([A-Za-z0-9][A-Za-z0-9.\-_]+@[A-Za-z][A-Za-z0-9]+)(?![A-Za-z0-9]|\.[A-Za-z])""")

    private const val NAME = """([A-Za-z0-9][A-Za-z0-9&'@._\-*/ ]{0,40}?)"""
    /** "Mr. ", "Ms. ", "M/s ": dropped so the dot doesn't end the name. */
    private const val HONORIFIC = """(?:(?:mr|mrs|ms|miss|dr|smt|shri|sri|m/s)\.?\s+)?"""
    private const val END =
        """(?=\s+(?:on|via|ref|using|for|avl|upi|txn|dated|at|from|with|is|has|thru|through|by|umrn|against|ac|a/c|dt|not|ist)\b""" +
            """|\s+\d{1,2}[-/]\d{1,2}[-/]\d|\s+[A-Za-z]+\s+(?:id|no\.?)\s*:|\s*[,;(\n]|\.\s|\.?\s*$)"""
    private val merchantPatterns = listOf(
        Regex("""\bmerchant(?:\s*name)?\s*:\s*$NAME$END""", I),
        Regex("""\bat\s+[._*]*$HONORIFIC$NAME$END""", I),
        // YES BANK style: "spent on Card X3333 @UPI_SHOP NAME 03-09-2026"
        // The name runs up to the date, so "HIGH ON CHAI" keeps its "ON".
        Regex("""\s@\s?(?:UPI_)?([A-Za-z0-9][^\n@]{0,40}?)(?=\s+\d{1,2}[-/]\d{1,2}[-/]\d)""", I),
        Regex("""\b(?:to|towards|in favou?r of)\s+(?:vpa\s+)?$HONORIFIC$NAME$END""", I),
        Regex(""";\s*([A-Za-z0-9][A-Za-z0-9&'._\- ]{0,40}?)\s+credited""", I),
        Regex("""\binfo\s*[:\-]?\s*$NAME$END""", I),
        // "… on 31-Aug-26 on AMAZON PAY IN" (merchant after the date)
        Regex("""(?<=\d)\s+on\s+$NAME$END""", I),
        // Axis style: "AUTOBPay/AirtelPost/625409", "UPI/P2M/1234/SWIGGY"
        Regex("""\b(?:UPI|IMPS|NEFT|RTGS|AUTOBPay|ACH|POS)/(?:[A-Z0-9]{2,4}/)?(?:\d+/)?([A-Za-z][A-Za-z0-9.&*\-]*)""", I),
    )
    private val merchantPrefix = Regex("""^(?:ACH\s*D-\s*|UPI[-/])""", I)
    private val notAMerchant = Regex(
        """^(?:(?:a/?c|ac|acct|account|your|card|rs|inr|xx|beneficiary|bank|mobile|vpa)\b|x+\d|\d+(?:[:/.\-]\d+)*$|\d{5,}|""" +
            """\d{1,2}[-/. ]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b)""",
        I,
    )

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dmyNumeric = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)""")
    private val dmyText = Regex("""(?<!\d)(\d{1,2})[- ]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-, ]*'?(\d{4}|\d{2})(?!\d)""", I)
    private val ymd = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val timePattern = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?(?:\s*([ap])\.?m\.?)?(?!\d)""", I)

    val datePatterns get() = listOf(dmyNumeric, dmyText, ymd)
    val timeRegex get() = timePattern

    /** Null when the SMS has no money amount at all: it is not stored anywhere (A4). */
    fun parse(body: String, home: Currency): ParsedSms? {
        val amounts = Amounts.find(body)
        if (amounts.isEmpty()) return null

        val main = amounts.firstOrNull { !it.isBalance && !it.isEquivalent }
        val homeAmount = if (main != null && main.money.currency.code != home.code) {
            amounts.firstOrNull { it.isEquivalent && it.money.currency.code == home.code }
                ?: amounts.firstOrNull {
                    it !== main && !it.isBalance && it.money.currency.code == home.code && it.range.first > main.range.first
                }
        } else null

        val account = findAccount(body)
        val vpa = vpaPattern.find(body)?.groupValues?.get(1)
        return ParsedSms(
            direction = direction(body),
            amount = main?.money,
            homeAmount = homeAmount?.money,
            ambiguousCurrency = main?.ambiguousCurrency ?: false,
            accountLast4 = account?.first,
            accountIsCard = account?.second ?: false,
            toAccountLast4 = findToAccount(body, account?.first),
            vpa = vpa,
            merchant = findMerchant(body, vpa),
            date = findDate(body),
            time = findTime(body),
        )
    }

    fun direction(body: String): Direction {
        if (notATxn.containsMatchIn(body)) return Direction.UNKNOWN
        if (refundWords.containsMatchIn(body)) return Direction.CREDIT
        val debit = debitWords.find(body)?.range?.first
        val credit = creditWords.find(body)?.range?.first
        return when {
            debit == null && credit == null -> Direction.UNKNOWN
            credit == null -> Direction.DEBIT
            debit == null -> Direction.CREDIT
            // "debited from A/c X; merchant credited": the first verb says what happened to *your* money.
            debit < credit -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    /** Last 4 digits of the user's account or card, and whether it is a card. */
    private fun findAccount(body: String): Pair<String, Boolean>? {
        // The first account mentioned is the one the money left from; later ones are "Not you? SMS BLOCK …".
        val first = listOf(accountWithMask, accountPlain, accountEnding)
            .mapNotNull { it.find(body) }
            .minByOrNull { it.range.first }
        if (first != null) {
            val kind = first.groupValues[1].lowercase()
            return first.groupValues[2].takeLast(4) to (kind == "card" || kind == "cc" || isCardContext(body, first.range.first))
        }
        bareMask.find(body)?.let { m ->
            return m.groupValues[1].takeLast(4) to Regex("""\bcard\b""", I).containsMatchIn(body)
        }
        return null
    }

    private val toContext = Regex("""\bto\b|transfer|benef|favou?r""", I)

    /** A later account mention that the money went to; "SMS BLOCK CC 4444" style mentions are skipped. */
    private fun findToAccount(body: String, from: String?): String? {
        val mentions = listOf(accountWithMask, accountPlain, bareMask)
            .flatMap { p -> p.findAll(body).map { it.range to it.groupValues.last().takeLast(4) } }
            .distinctBy { it.first.last }
            .sortedBy { it.first.first }
        for ((range, last4) in mentions.drop(1)) {
            if (last4 == from) continue
            val before = body.substring(maxOf(0, range.first - 25), range.first)
            val after = body.substring(range.last + 1, minOf(body.length, range.last + 16))
            if (Regex("""block""", I).containsMatchIn(before)) continue
            if (toContext.containsMatchIn(before) || after.contains("credited", ignoreCase = true)) return last4
        }
        return null
    }

    /** "ICICI Bank Card XX6666" with the keyword "ending" alone: look just before it for "card". */
    private fun isCardContext(body: String, at: Int): Boolean =
        Regex("""\bcard\b""", I).containsMatchIn(body.substring(maxOf(0, at - 25), minOf(body.length, at + 6)))

    private fun findMerchant(body: String, vpa: String?): String? {
        for (p in merchantPatterns) {
            for (m in p.findAll(body)) {
                val name = merchantPrefix.replace(m.groupValues[1].trim(), "").trimEnd('.', '-', '/', '_').trim()
                if (name.isEmpty() || notAMerchant.containsMatchIn(name)) continue
                if (timePattern.matches(name)) continue
                if (vpa != null && name.equals(vpa, ignoreCase = true)) return vpa
                return name
            }
        }
        return vpa
    }

    /** The first date written in the SMS, read day-first as Indian banks write it. Null if none or invalid (B7a). */
    fun findDate(body: String): LocalDate? {
        val hits = buildList {
            dmyNumeric.find(body)?.let { m ->
                add(m.range.first to date(m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1]))
            }
            dmyText.find(body)?.let { m ->
                val month = months.indexOf(m.groupValues[2].lowercase()) + 1
                add(m.range.first to date(m.groupValues[3], month, m.groupValues[1]))
            }
            ymd.find(body)?.let { m ->
                add(m.range.first to date(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3]))
            }
        }
        return hits.minByOrNull { it.first }?.second
    }

    private fun date(year: String, month: Int, day: String): LocalDate? {
        val y = year.toInt().let { if (year.length == 2) 2000 + it else it }
        return runCatching { LocalDate(y, month, day.toInt()) }.getOrNull()
    }

    fun findTime(body: String): LocalTime? {
        val m = timePattern.find(body) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val second = m.groupValues[3].toIntOrNull() ?: 0
        when (m.groupValues[4].lowercase()) {
            "p" -> if (hour in 1..11) hour += 12
            "a" -> if (hour == 12) hour = 0
        }
        return runCatching { LocalTime(hour, minute, second) }.getOrNull()
    }
}
