package app.piggybank.review

import app.piggybank.core.Currency
import app.piggybank.db.PiggyDatabase
import app.piggybank.db.Rule
import app.piggybank.db.SelectForClassify
import app.piggybank.sms.Direction
import app.piggybank.sms.Signature
import app.piggybank.sms.SmsParser
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** The outcome of classifying one payment. [decided] = no review needed (a rule or a sure detection). */
data class Tag(
    val kind: String,
    val categoryId: Long?,
    val needWant: String?,
    val decided: Boolean,
    val ruleId: Long? = null,
    /** Why, shown on the card: "Your rule", "Money arrived in ••2222", "Looks like groceries". */
    val reason: String,
    /** A kind to pre-select in review without applying it (self-transfers are only marked by the user). */
    val suggestKind: String? = null,
)

/**
 * Step 4 of the SMS pipeline (PLAN §5), in order:
 * 1. own-account transfer (D1, D2), 2. credit card bill (D4), 3. the user's rules (E1–E7), 4. a guess.
 * Only touches payments nobody has decided yet, so it is safe to run after every scan.
 */
class Classifier(
    private val db: PiggyDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val tz: TimeZone = TimeZone.currentSystemDefault(),
) {

    private val categories by lazy { db.categoryQueries.selectActive().executeAsList().associateBy { it.name } }
    private val rules by lazy { db.ruleQueries.selectAll().executeAsList() }

    /** Classifies every undecided payment. Returns how many were tagged. */
    fun run(): Int = db.transactionWithResult {
        Duplicates.autoMark(db)
        var n = 0
        for (row in db.txnQueries.selectForClassify().executeAsList()) {
            val tag = classify(row) ?: continue
            db.txnQueries.tag(
                kind = tag.kind,
                category_id = tag.categoryId,
                need_want = tag.needWant,
                tag_source = if (tag.decided) "RULE" else "GUESS",
                rule_id = tag.ruleId,
                reviewed_at = if (tag.decided) now() else null,
                id = row.id,
            )
            db.txnQueries.setSuggestion(tag.suggestKind, tag.reason.takeIf { tag.suggestKind != null }, row.id)
            if (tag.decided && tag.ruleId != null) db.ruleQueries.recordHit(now(), tag.ruleId)
            n++
        }
        n
    }

    fun classify(row: SelectForClassify): Tag? {
        val body = row.body ?: ""
        val parsed = if (body.isNotEmpty()) SmsParser.parse(body, Currency.INR) else null
        val fromLast4 = row.account_id?.let { db.accountQueries.selectById(it).executeAsOneOrNull()?.last4 }

        // 1–2. Money moving to an account or card the user owns.
        parsed?.toAccountLast4?.takeIf { it != fromLast4 }?.let { to ->
            val own = db.accountQueries.selectOwnByLast4(to).executeAsList()
            if (own.isNotEmpty()) {
                return if (own.any { it.kind == "CARD" }) excluded("CC_BILL", "Card bill payment", "Paid to your card ••$to")
                else suggestTransfer("To your account ••$to")
            }
        }
        pairedCredit(row, fromLast4)?.let { return it }
        if (CARD_BILL.containsMatchIn(row.counterparty_raw ?: "") || CARD_BILL_BODY.containsMatchIn(body)) {
            return excluded("CC_BILL", "Card bill payment", "Looks like a card bill payment")
        }

        // 3. The user's rules.
        matchRule(row)?.let { rule ->
            return Tag(
                kind = rule.result_kind,
                categoryId = rule.result_category_id,
                needWant = rule.result_need_want,
                decided = rule.always_ask == 0L,
                ruleId = rule.id,
                reason = if (rule.always_ask == 1L) "You asked to always check this one" else "Your rule",
            )
        }

        // 4. A guess from the merchant name. People are never guessed (E4).
        val name = nameKey(row.counterparty_raw, row.vpa)
        if (looksLikePerson(name, row.vpa)) return null
        val haystack = listOfNotNull(name, row.vpa?.uppercase(), if (FOLIO.containsMatchIn(body)) "FOLIO" else null)
            .joinToString(" ")
        for (g in GUESSES) {
            if (g.pattern.containsMatchIn(haystack)) {
                val cat = categories[g.category] ?: continue
                return Tag(g.kind, cat.id, cat.default_need_want, decided = false, reason = "Looks like ${g.category.lowercase()}")
            }
        }
        return null
    }

    /**
     * Self-transfers are never marked automatically: the payment stays a spend (and counts) until the
     * user marks it in review, where "Self-transfer" is pre-selected with this reason.
     */
    private fun suggestTransfer(reason: String) =
        Tag("SPEND", null, null, decided = false, reason = reason, suggestKind = "SELF_TRANSFER")

    private fun excluded(kind: String, category: String, reason: String) =
        Tag(kind, categories[category]?.id, null, decided = true, reason = reason)

    /**
     * D1: the same amount arrives in another own account within an hour, or (for a card bill)
     * a "payment received" on a card within 3 days.
     */
    private fun pairedCredit(row: SelectForClassify, fromLast4: String?): Tag? {
        val sent = row.sms_received_at ?: return null
        val credits = db.smsQueries.selectIgnoredBetween(sent - HOUR, sent + 3 * DAY).executeAsList()
        for (c in credits) {
            if (REFUND.containsMatchIn(c.body)) continue
            val p = SmsParser.parse(c.body, Currency.INR) ?: continue
            if (p.direction != Direction.CREDIT || p.amount == null) continue
            if (p.amount.minor != row.orig_amount_minor || p.amount.currency.code != row.orig_currency) continue
            val to = p.accountLast4 ?: continue
            if (to == fromLast4) continue
            val isCard = p.accountIsCard || CARD_WORD.containsMatchIn(c.body)
            if (isCard) return excluded("CC_BILL", "Card bill payment", "Payment received on your card ••$to")
            if (kotlin.math.abs(c.received_at - sent) <= HOUR) {
                return suggestTransfer("Money arrived in your account ••$to")
            }
        }
        return null
    }

    /** E7: an exact UPI ID beats an exact name, which beats a partial name, then sender, then body. */
    private fun matchRule(row: SelectForClassify): Rule? {
        val vpa = row.vpa?.lowercase()
        val name = nameKey(row.counterparty_raw, row.vpa)
        val sender = row.sender?.let { Signature.senderKey(it) }
        val body = row.body ?: ""
        return rules.mapNotNull { r ->
            val score = when (r.field_) {
                "VPA" -> if (vpa != null && r.match_type == "EXACT" && r.pattern == vpa) 40 else null
                "MERCHANT" -> when {
                    name == null -> null
                    r.match_type == "EXACT" && r.pattern == name -> 30
                    r.match_type == "PREFIX" && name.startsWith(r.pattern) -> 20
                    r.match_type == "CONTAINS" && name.contains(r.pattern) -> 15
                    else -> null
                }
                "SENDER" -> if (sender != null && r.pattern == sender) 5 else null
                "BODY" -> if (r.match_type == "CONTAINS" && body.contains(r.pattern, ignoreCase = true)) 3 else null
                else -> null
            }
            score?.let { it + r.priority.toInt() to r }
        }.maxByOrNull { it.first }?.second
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        private val I = RegexOption.IGNORE_CASE
        private val CARD_BILL = Regex("""credit card|card repa|card bill|cc bill|\bcred\b|cred club|cardbill""", I)
        private val CARD_BILL_BODY = Regex("""towards (?:your )?(?:\w+ )?credit card|credit card (?:bill|payment|dues)|billdesk\W+\w*card""", I)
        private val CARD_WORD = Regex("""\bcard\b""", I)
        private val REFUND = Regex("""refund|reversal|reversed|cashback""", I)
        private val FOLIO = Regex("""\bfolio\b|\bnav\b|\bunits\b""", I)

        /** The name used to match rules: the payee name, or the UPI ID's name part ("swiggy@icici" → "SWIGGY"). */
        fun nameKey(counterparty: String?, vpa: String?): String? {
            val raw = counterparty?.takeIf { !it.equals(vpa, ignoreCase = true) } ?: vpa?.substringBefore('@')
            return raw?.uppercase()?.replace(Regex("[^A-Z0-9]+"), " ")?.trim()?.ifEmpty { null }
        }

        private val BUSINESS = Regex(
            """\b(?:LTD|LIMITED|PVT|PRIVATE|INDIA|SERVICES?|STORES?|SHOP|MART|CENT(?:RE|ER)|TRADERS|AGENC\w*|CORP\w*|TECH\w*|""" +
                """SOLUTIONS?|HUB|GROUP|CLUB|PAY|BANK|ENTERPRISES?|COMPANY|CO|LLP|INC|HOTEL|CAFE|BAKERY|FOODS?|""" +
                """KITCHEN|RESTAURANT|HOSPITAL|CLINIC|MEDICALS?|PHARMA\w*|FUEL|PETROL|TOLL|PLAZA|STATION|SCHOOL|COLLEGE|""" +
                """TIFFINS?|FRUITS?|JUICE|SWEETS?|CHAT|CHAI|TEA|COFFEE|BAR|SUPER|FRESH|PROVISIONS|GENERAL|FANCY|""" +
                """PRINTERS|JUNCTION|CORNER|HUT|POINT|ZONE|WORLD|EXPRESS|ONLINE|DIGITAL|MOTORS|AUTO|TRAVELS?|TOURS?)\b""",
        )

        /** E4: payments to people always go to review and never get an automatic rule by default. */
        fun looksLikePerson(name: String?, vpa: String?): Boolean {
            val local = vpa?.substringBefore('@')
            if (local != null && local.count { it.isDigit() } >= 10) return true
            if (name == null) return false
            if (BUSINESS.containsMatchIn(name) || GUESSES.any { it.pattern.containsMatchIn(name) }) return false
            val words = name.split(' ')
            // One long run-together word ("ANTHROPICCLAUDESUB", "RAZZOMATOONLINEORDER") is a merchant code, not a name.
            if (words.size == 1 && words[0].length >= 12) return false
            return words.size in 1..4 && words.all { w -> w.all { it.isLetter() } }
        }

        private class Guess(val category: String, val kind: String, regex: String) {
            val pattern = Regex(regex, I)
        }

        private val GUESSES = listOf(
            Guess("Mutual fund SIP", "INVESTMENT", """MUTUAL ?FUND|NIPPON|\bSIP\b|FOLIO|KUVERA|ET ?MONEY|GROWW|PPFAS|\bMF\b|PROTEAN|\bNPS\b"""),
            Guess("Stocks", "INVESTMENT", """ZERODHA|UPSTOX|INDMONEY|ANGEL ?ONE|SMALLCASE|\bDHAN\b"""),
            Guess("Food delivery", "SPEND", """SWIGGY|ZOMATO|EATSURE|DOMINOS|PIZZA ?HUT|FAASOS"""),
            Guess("Groceries", "SPEND", """ZEPTO|BLINKIT|BIGBASKET|BB ?NOW|DMART|INSTAMART|PROVISION|SUPER ?MAR|KIRANA|FRUITS?\b|VEGETABLE|DAIRY|\bMILK|GREEN ?CHOICE|FRESH"""),
            Guess("Commute", "SPEND", """\bUBER|\bOLA\b|RAPIDO|IRCTC|REDBUS|\bMETRO|TOLL|FASTAG|FUEL|PETROL|FILLING|\bHPCL|\bBPCL|\bIOCL|INDIAN ?OIL|PARKING|PARKER|NAMMA ?YATRI|MAKEMYTRIP|INDIGO|AIRLINE|\bHSD\b"""),
            Guess("Bills & utilities", "SPEND", """AIRTEL|\bJIO\b|\bBSNL|BESCOM|ELECTRIC|WATER ?BILL|INDANE|\bACT\b|ATRIA|BROADBAND|\bDTH\b|TATA ?PLAY|RECHARGE|MYGATE|INSURANCE|LIFE ?INS|HDFC ?LIFE"""),
            Guess("Health", "SPEND", """PHARM|MEDIC|\b1 ?MG|APOLLO|HOSPITAL|CLINIC|DIAGNOS|\bLABS?\b|HEALTH"""),
            Guess("Entertainment", "SPEND", """NETFLIX|SPOTIFY|HOTSTAR|GOOGLE ?PLAY|BOOKMYSHOW|DISTRICT|\bPVR|\bINOX|STEAM|YOUTUBE|CINEMA|THEAT|PREPLY|XSTREAM|RACING"""),
            Guess("Cafés", "SPEND", """CAFE|COFFEE|CHAI|\bTEA\b|BAKER|TIFFIN|HOTEL|RESTAURANT|KITCHEN|DHABA|BHAVAN|JUICE|\bCHATS?\b|SWEET|BIRYANI|DOSA|FOODS|RUCHULU|IDLI|KABAB|PANIPURI|\bBAR\b|SODA|NAIVEDYAM|GRAND\b|THINDI"""),
            Guess("Shopping", "SPEND", """AMAZON|FLIPKART|MYNTRA|AJIO|ZUDIO|WESTSIDE|TRENDS|DECATHLON|NYKAA|MEESHO|CARAT|TANISHQ|FASHION|FANCY|TAO ?BAO|SPORTS|PUBLICATIONS|PRINTERS"""),
        )
    }
}
