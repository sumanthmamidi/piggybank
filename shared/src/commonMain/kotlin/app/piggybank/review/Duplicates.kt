package app.piggybank.review

import app.piggybank.db.PiggyDatabase
import app.piggybank.db.SelectForDuplicateCheck
import app.piggybank.sms.Signature

/**
 * A6e (decided 23 Nov 2026): one payment reported twice by the same bank arrives as two *different* SMS
 * formats (e.g. "PAYMENT ALERT" and "UPDATE: debited", or "AutoPay Success" and "Amount Spent"). When the
 * sender, amount and account match and the two arrive within 4 hours, it's marked as a duplicate
 * without asking. The same format twice is always two real payments (the balance drops twice) and is
 * never touched. Pairs the user separated or un-duplicated are never marked again.
 */
object Duplicates {
    private const val WINDOW_MS = 4 * 60 * 60 * 1000L

    fun autoMark(db: PiggyDatabase): Int {
        val dismissed = db.txnQueries.selectDismissedPairs().executeAsList().map { it.txn_a to it.txn_b }.toSet()
        val rows = db.txnQueries.selectForDuplicateCheck().executeAsList()
            .filter { it.account_id != null && it.template_id != null }
            .sortedBy { it.received_at }
        val gone = mutableSetOf<Long>()
        var marked = 0
        for ((i, a) in rows.withIndex()) {
            if (a.id in gone) continue
            for (b in rows.drop(i + 1)) {
                if (b.received_at - a.received_at > WINDOW_MS) break
                if (b.id in gone || !samePayment(a, b)) continue
                if ((minOf(a.id, b.id) to maxOf(a.id, b.id)) in dismissed) continue
                // Keep the copy that's already tagged, then the one naming the payee, then the earlier one.
                val (keep, dup) = if (rank(b) > rank(a)) b to a else a to b
                db.txnQueries.markDuplicate(keep.id, "AUTO", dup.id)
                gone += dup.id
                marked++
                if (dup.id == a.id) break
            }
        }
        return marked
    }

    private fun samePayment(a: SelectForDuplicateCheck, b: SelectForDuplicateCheck) =
        a.home_amount_minor == b.home_amount_minor &&
            a.account_id == b.account_id &&
            a.template_id != b.template_id &&
            Signature.senderKey(a.sender) == Signature.senderKey(b.sender) &&
            samePayee(a.counterparty_raw, b.counterparty_raw)

    /**
     * Two SMS that both name a payee must name the same one. Banks shorten names differently
     * ("HDFC Life Insurance Company Lt" vs "HDFCStandardLifeInsu-2945"), so sharing a run of 6+ letters
     * ("lifeinsu") is enough; "C KUMAR" vs "Raju" share nothing like that.
     */
    private fun samePayee(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        val x = a.lowercase().filter { it.isLetter() }
        val y = b.lowercase().filter { it.isLetter() }
        if (x.isEmpty() || y.isEmpty()) return true
        return longestCommonRun(x, y) >= minOf(6, x.length, y.length)
    }

    private fun longestCommonRun(x: String, y: String): Int {
        var best = 0
        val prev = IntArray(y.length + 1)
        for (i in 1..x.length) {
            var diag = 0
            for (j in 1..y.length) {
                val keep = prev[j]
                prev[j] = if (x[i - 1] == y[j - 1]) diag + 1 else 0
                if (prev[j] > best) best = prev[j]
                diag = keep
            }
        }
        return best
    }

    private fun rank(r: SelectForDuplicateCheck) = (if (r.reviewed_at != null) 2 else 0) + (if (r.counterparty_raw != null) 1 else 0)
}
