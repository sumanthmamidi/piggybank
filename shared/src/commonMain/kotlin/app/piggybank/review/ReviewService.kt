package app.piggybank.review

import app.piggybank.db.PiggyDatabase
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/**
 * Everything the user decides in the end-of-day review. Each decision also teaches the app:
 * a confirmed payment allows its SMS format, "Not money" blocks it, "Always tag" creates a rule.
 */
class ReviewService(
    private val db: PiggyDatabase,
    private val tz: TimeZone,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Tags one or more payments (a merchant group). With [remember], creates or updates a rule
     * for that merchant/UPI ID and applies it to other undecided payments (E1, E3).
     */
    fun tag(ids: List<Long>, kind: String, categoryId: Long?, needWant: String?, remember: Boolean) {
        db.transaction {
            val at = now()
            val nw = if (kind == "SPEND") needWant else null // E8: investments and hidden kinds have no Need/Want
            var ruleId: Long? = null
            if (remember) {
                val first = db.txnQueries.selectById(ids.first()).executeAsOne()
                ruleId = upsertRule(first.vpa, first.counterparty_raw, kind, categoryId, nw, first.id)
            }
            for (id in ids) {
                db.txnQueries.tag(kind, categoryId, nw, "USER", ruleId, at, id)
                whitelistFormatOf(id, at)
            }
        }
        if (remember) Classifier(db, now, tz).run()
    }

    private fun upsertRule(vpa: String?, counterparty: String?, kind: String, categoryId: Long?, needWant: String?, fromTxn: Long): Long? {
        val (field, pattern) = when {
            vpa != null -> "VPA" to vpa.lowercase()
            else -> "MERCHANT" to (Classifier.nameKey(counterparty, null) ?: return null)
        }
        val existing = db.ruleQueries.selectByKey(field, "EXACT", pattern).executeAsOneOrNull()
        if (existing != null) {
            db.ruleQueries.updateResult(kind, categoryId, needWant, existing.always_ask, existing.id)
            return existing.id
        }
        db.ruleQueries.insert(field, "EXACT", pattern, kind, categoryId, needWant, 0, 0, fromTxn)
        return db.ruleQueries.lastInsertId().executeAsOne()
    }

    /** C1: confirming a payment marks its SMS format as a known payment format. */
    private fun whitelistFormatOf(txnId: Long, at: Long) {
        val smsId = db.txnQueries.selectById(txnId).executeAsOne().sms_id ?: return
        val templateId = db.smsQueries.selectSmsById(smsId).executeAsOneOrNull()?.template_id ?: return
        db.smsQueries.whitelistIfPending(at, templateId)
    }

    /** "This isn't a payment" on a parsed SMS: the transaction goes, the SMS format is blocked. */
    fun notAPayment(txnId: Long) = db.transaction {
        val txn = db.txnQueries.selectById(txnId).executeAsOneOrNull() ?: return@transaction
        txn.sms_id?.let { blockSms(it) }
        db.txnQueries.clearDuplicatesOf(txnId)
        db.txnQueries.deleteDismissedFor(txnId)
        db.txnQueries.delete(txnId)
    }

    /** C2: "Not money" on a candidate. Every other undecided SMS in that format is settled too. */
    fun notMoney(smsId: Long) = db.transaction { blockSms(smsId) }

    private fun blockSms(smsId: Long) {
        db.smsQueries.setSmsStatus("NOT_MONEY", smsId)
        val templateId = db.smsQueries.selectSmsById(smsId).executeAsOneOrNull()?.template_id ?: return
        db.smsQueries.setTemplateVerdict("BLACKLIST", "USER", now(), templateId)
        db.smsQueries.ignoreCandidatesOfTemplate(templateId)
    }

    /** F5: "It's a payment" on a candidate SMS. It becomes a transaction in today's review. */
    fun pickCandidate(smsId: Long, tz: TimeZone = this.tz): Long? {
        val id = db.transactionWithResult {
            val txnId = SmsIngestor(db, tz).storePicked(smsId) ?: return@transactionWithResult null
            db.smsQueries.setSmsStatus("USER_PICKED", smsId)
            db.smsQueries.selectSmsById(smsId).executeAsOne().template_id?.let { db.smsQueries.whitelistIfPending(now(), it) }
            txnId
        }
        if (id != null) Classifier(db, now, tz).run()
        return id
    }

    /** A6b: out of every total, kept in the Duplicates list. The SMS format stays allowed. */
    fun markDuplicate(id: Long, of: Long) = db.txnQueries.markDuplicate(of, "USER", id)

    /** A6c */
    fun undoDuplicate(id: Long) = db.transaction {
        val of = db.txnQueries.selectById(id).executeAsOneOrNull()?.duplicate_of
        db.txnQueries.clearDuplicate(id)
        // Undone means "these are two payments": never mark this pair again, by hand or automatically.
        if (of != null) db.txnQueries.dismissDuplicatePair(minOf(id, of), maxOf(id, of))
    }

    /** A6d: both stay, and this pair is never suggested again. */
    fun separatePayments(a: Long, b: Long) = db.txnQueries.dismissDuplicatePair(minOf(a, b), maxOf(a, b))

    /** D9: a hidden transfer or bill payment moves into spends and comes back to review for a category. */
    fun countIt(id: Long) = db.txnQueries.countIt(id)

    /** F2, F3, F4: closing a day the day after a closed day extends the streak; a gap resets it to 1. */
    fun closeDay(date: LocalDate, txnCount: Int): Long = db.transactionWithResult {
        val key = date.toString()
        db.appQueries.selectDay(key).executeAsOneOrNull()?.let { return@transactionWithResult it.streak }
        val yesterday = db.appQueries.selectDay(date.minus(DatePeriod(days = 1)).toString()).executeAsOneOrNull()
        val streak = (yesterday?.streak ?: 0) + 1
        db.appQueries.closeDay(key, now(), txnCount.toLong(), streak)
        streak
    }

    /** The current streak: the last closed day counts only if it was today or yesterday (F4). */
    fun streak(today: LocalDate): Long {
        val last = db.appQueries.selectLatestClosed().executeAsOneOrNull() ?: return 0
        val lastDate = LocalDate.parse(last.date)
        return if (lastDate == today || lastDate == today.minus(DatePeriod(days = 1))) last.streak else 0
    }

    /** F1, F8: change an auto-tagged or already-reviewed payment. */
    fun reopen(id: Long) = db.txnQueries.reopen(id)

    fun deleteRule(id: Long) = db.transaction {
        db.txnQueries.detachRule(id)
        db.ruleQueries.delete(id)
    }

    /** E5: a merchant that should always be checked by hand (e.g. Amazon). */
    fun setAlwaysAsk(ruleId: Long, on: Boolean) = db.ruleQueries.setAlwaysAsk(if (on) 1 else 0, ruleId)
}
