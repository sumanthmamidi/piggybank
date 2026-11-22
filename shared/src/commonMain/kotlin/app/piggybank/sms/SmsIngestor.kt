package app.piggybank.sms

import app.piggybank.core.Currency
import app.piggybank.core.Money
import app.piggybank.db.PiggyDatabase
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

/** One SMS as read from the phone's inbox. */
data class IncomingSms(
    val androidId: Long?,
    val sender: String,
    val body: String,
    /** When the phone received it (epoch millis). */
    val receivedAt: Long,
    /** The SMS centre's send time, 0 if unknown. Same for both copies of a re-delivered SMS. */
    val sentAt: Long,
)

data class IngestSummary(
    val read: Int = 0,
    val notMoney: Int = 0,
    val alreadyStored: Int = 0,
    val payments: Int = 0,
    val candidates: Int = 0,
    val ignored: Int = 0,
) {
    operator fun plus(o: IngestSummary) = IngestSummary(
        read + o.read, notMoney + o.notMoney, alreadyStored + o.alreadyStored,
        payments + o.payments, candidates + o.candidates, ignored + o.ignored,
    )
}

/**
 * Steps 1–3 of the SMS pipeline (PLAN §5): money filter, dedupe, template lookup, generic parse,
 * and storage. Classification and tagging (step 4) come in M2; every payment is stored as SPEND.
 */
class SmsIngestor(private val db: PiggyDatabase, private val tz: TimeZone) {

    fun ingest(batch: List<IncomingSms>): IngestSummary = db.transactionWithResult {
        val home = Currency.of(db.appQueries.getSetting("home_currency").executeAsOneOrNull() ?: "INR")
        batch.fold(IngestSummary()) { acc, sms -> acc + ingestOne(sms, home) }
    }

    private fun ingestOne(sms: IncomingSms, home: Currency): IngestSummary {
        val parsed = SmsParser.parse(sms.body, home) ?: return IngestSummary(read = 1, notMoney = 1)

        val key = Signature.senderKey(sms.sender)
        val stamp = (if (sms.sentAt > 0) sms.sentAt else sms.receivedAt) / 1000
        val hash = Sha256.hex("$key\n${sms.body}\n$stamp")
        if (db.smsQueries.selectIdByHash(hash).executeAsOneOrNull() != null) {
            return IngestSummary(read = 1, alreadyStored = 1)
        }

        val signature = Signature.of(key, sms.body, parsed)
        db.smsQueries.insertTemplate(key, signature)
        val template = db.smsQueries.selectTemplateBySignature(signature).executeAsOne()

        val status = when {
            template.verdict == "BLACKLIST" -> "IGNORED"
            parsed.isPayment -> "PARSED"
            // Money in is never recorded (B5). The SMS is kept so M2 can pair it with a self-transfer.
            parsed.direction == Direction.CREDIT -> "IGNORED"
            else -> "CANDIDATE"
        }
        db.smsQueries.insertSms(sms.androidId, sms.sender, sms.body, sms.receivedAt, hash, template.id, status)
        val smsId = db.smsQueries.lastInsertId().executeAsOne()
        db.smsQueries.bumpTemplate(smsId = smsId, id = template.id)

        // Any account an SMS on this phone says was credited belongs to the user: remember it, so a
        // later debit naming it, or a matching credit, is recognised as a self-transfer (D1, D2).
        if (parsed.direction == Direction.CREDIT && parsed.accountLast4 != null && template.verdict != "BLACKLIST") {
            accountFor(key, parsed.accountLast4, parsed.accountIsCard)
        }

        return when (status) {
            "PARSED" -> {
                storePayment(smsId, sms, parsed, key, home)
                IngestSummary(read = 1, payments = 1)
            }
            "CANDIDATE" -> IngestSummary(read = 1, candidates = 1)
            else -> IngestSummary(read = 1, ignored = 1)
        }
    }

    /**
     * F5: the user says a stored SMS *is* a payment. Uses the first amount that isn't a balance
     * (or the first amount at all). Returns the new transaction id, or null if the SMS has no amount.
     */
    fun storePicked(smsId: Long): Long? {
        val raw = db.smsQueries.selectSmsById(smsId).executeAsOneOrNull() ?: return null
        val home = Currency.of(db.appQueries.getSetting("home_currency").executeAsOneOrNull() ?: "INR")
        val parsed = SmsParser.parse(raw.body, home) ?: return null
        val amount = parsed.amount ?: Amounts.find(raw.body).firstOrNull()?.money ?: return null
        val sms = IncomingSms(raw.android_sms_id, raw.sender, raw.body, raw.received_at, 0)
        db.txnQueries.selectBySmsId(smsId).executeAsOneOrNull()?.let { return it.id }
        storePayment(smsId, sms, parsed.copy(direction = Direction.DEBIT, amount = amount), Signature.senderKey(raw.sender), home)
        return db.txnQueries.lastInsertId().executeAsOne()
    }

    private fun storePayment(smsId: Long, sms: IncomingSms, p: ParsedSms, senderKey: String, home: Currency) {
        val amount = p.amount!!
        val (occurredAt, dateSource) = occurredAt(sms.receivedAt, p)
        val fx = homeValue(amount, p.homeAmount, home)
        val accountId = p.accountLast4?.let { accountFor(senderKey, it, p.accountIsCard) }
        val merchantKey = p.vpa?.lowercase()
            ?: p.merchant?.uppercase()?.replace(Regex("[^A-Z0-9]+"), " ")?.trim()?.ifEmpty { null }

        db.txnQueries.insert(
            sms_id = smsId,
            occurred_at = occurredAt,
            date_source = dateSource,
            orig_currency = amount.currency.code,
            orig_amount_minor = amount.minor,
            home_amount_minor = fx.homeMinor,
            fx_rate = fx.rate,
            fx_source = fx.source,
            account_id = accountId,
            counterparty_raw = p.merchant ?: p.vpa,
            vpa = p.vpa,
            merchant_key = merchantKey,
            kind = "SPEND",
        )
    }

    /**
     * The date written in the SMS wins (B7); with no readable date, the arrival time is used (B7a).
     * A written date more than a day after arrival or over 400 days before it is treated as unreadable.
     */
    internal fun occurredAt(receivedAt: Long, p: ParsedSms): Pair<Long, String> {
        val arrival = Instant.fromEpochMilliseconds(receivedAt)
        val date = p.date ?: return receivedAt to "ARRIVAL"
        val arrivalLocal = arrival.toLocalDateTime(tz)
        val time = p.time ?: if (date == arrivalLocal.date) arrivalLocal.time else LocalTime(12, 0)
        val at = LocalDateTime(date, time).toInstant(tz)
        if (at > arrival + 1.days || at < arrival - 400.days) return receivedAt to "ARRIVAL"
        return at.toEpochMilliseconds() to "SMS"
    }

    private data class Fx(val homeMinor: Long?, val rate: String?, val source: String)

    /**
     * M1–M3: same currency → as is; the SMS gives a home amount → use it; otherwise the user's last
     * rate for that currency. With no rate yet, the home amount stays empty and review asks for it
     * (fx_source USER_RATE with a null rate means "rate needed").
     */
    private fun homeValue(amount: Money, fromSms: Money?, home: Currency): Fx {
        if (amount.currency.code == home.code) return Fx(amount.minor, null, "SAME")
        if (fromSms != null) return Fx(fromSms.minor, rateString(amount, fromSms), "SMS")
        val stored = db.moneyQueries.selectRate(amount.currency.code).executeAsOneOrNull()
            ?: return Fx(null, null, "USER_RATE")
        val home = convert(amount, stored.rate_to_home, home) ?: return Fx(null, null, "USER_RATE")
        return Fx(home.minor, stored.rate_to_home, "USER_RATE")
    }

    companion object {
        private const val RATE_SCALE = 1_000_000L

        /** Home units per one unit of the original currency, 4 decimals, e.g. "84.5360". */
        fun rateString(orig: Money, home: Money): String {
            if (orig.minor == 0L) return "0"
            val num = home.minor * Money.pow10(orig.currency.decimals) * 10_000
            val den = orig.minor * Money.pow10(home.currency.decimals)
            val r = (num + den / 2) / den
            return "${r / 10_000}.${(r % 10_000).toString().padStart(4, '0')}"
        }

        /** orig × rate, rounded half-up to the home currency's smallest unit. */
        fun convert(orig: Money, rate: String, home: Currency): Money? {
            val parts = rate.trim().split('.')
            val whole = parts[0].toLongOrNull() ?: return null
            val frac = (parts.getOrNull(1) ?: "").take(6).padEnd(6, '0').toLongOrNull() ?: return null
            val scaled = whole * RATE_SCALE + frac
            val num = orig.minor * scaled * Money.pow10(home.decimals)
            val den = RATE_SCALE * Money.pow10(orig.currency.decimals)
            return Money((num + den / 2) / den, home)
        }
    }

    /** The account a debit came from is always one of the user's own; it is remembered on first sight. */
    fun accountFor(institution: String, last4: String, isCard: Boolean): Long {
        val kind = if (isCard) "CARD" else "BANK"
        db.accountQueries.selectByInstitutionLast4(institution, last4, kind).executeAsOneOrNull()?.let { return it.id }
        db.accountQueries.insert(kind, institution, last4, null, 1)
        return db.accountQueries.lastInsertId().executeAsOne()
    }
}
