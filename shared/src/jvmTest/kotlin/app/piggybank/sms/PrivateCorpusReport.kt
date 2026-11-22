package app.piggybank.sms

import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.test.Test

/**
 * Replays your own SMS (testdata/private/sms_corpus.tsv, git-ignored, never committed) through the
 * pipeline and writes testdata/private/report.txt. Skipped when the file isn't there, e.g. in CI.
 * Format per line: android_id \t sender \t received_at \t body (with \n, \t, \\ escaped).
 */
class PrivateCorpusReport {
    @Test fun report() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "testdata/private").isDirectory } ?: return
        val corpus = File(root, "testdata/private/sms_corpus.tsv").takeIf { it.exists() } ?: return

        val sms = corpus.readLines().filter { it.isNotBlank() }.map { line ->
            val (id, sender, received, body) = line.split('\t', limit = 4)
            IncomingSms(id.toLongOrNull(), sender, unescape(body), received.toLong(), 0)
        }
        val db = createDatabase(DriverFactory().create())
        val summary = SmsIngestor(db, TimeZone.of("Asia/Kolkata")).ingest(sms)

        val out = StringBuilder()
        out.appendLine("SUMMARY $summary")
        out.appendLine("templates ${db.smsQueries.countByStatus().executeAsList()}")
        val rows = db.txnQueries.selectRecentWithAccount(10_000).executeAsList()
        out.appendLine("payments ${rows.size}, no merchant ${rows.count { it.counterparty_raw == null }}, no account ${rows.count { it.last4 == null }}")
        out.appendLine("\n== PAYMENTS (amount | merchant | account | body)")
        db.smsQueries.selectByStatus("PARSED").executeAsList().forEach { r ->
            val p = SmsParser.parse(r.body, app.piggybank.core.Currency.INR)!!
            out.appendLine("${p.amount?.format()} | ${p.merchant ?: "∅"} | ${p.accountLast4 ?: "∅"}${if (p.accountIsCard) " card" else ""} | ${r.body.replace('\n', ' ').take(150)}")
        }
        out.appendLine("\n== CANDIDATES")
        db.smsQueries.selectByStatus("CANDIDATE").executeAsList().forEach { out.appendLine(it.body.replace('\n', ' ').take(170)) }
        // M2: classification over the same data.
        val tagged = app.piggybank.review.Classifier(db).run()
        val all = db.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList()
        out.appendLine("\n== CLASSIFIED $tagged of ${all.size}")
        all.groupBy { "${it.kind} ${it.tag_source} ${it.category_name}" }.entries.sortedByDescending { it.value.size }
            .forEach { (k, v) -> out.appendLine("${v.size}  $k") }
        out.appendLine("\n== AUTO DUPLICATES")
        db.txnQueries.selectDuplicates().executeAsList().forEach { d ->
            val kept = db.txnQueries.selectById(d.duplicate_of!!).executeAsOne()
            out.appendLine("${d.orig_amount_minor / 100} | dup: ${d.counterparty_raw} | kept: ${kept.counterparty_raw} | ${d.duplicate_by}")
        }
        out.appendLine("\n== STILL ASKED (possible duplicates)")
        all.filter { it.duplicate_of == null }.forEach { t ->
            db.txnQueries.selectPossibleDuplicate(t.id).executeAsOneOrNull()?.let { o -> out.appendLine("${t.orig_amount_minor / 100} | ${t.counterparty_raw} ~ ${o.counterparty_raw}") }
        }
        out.appendLine("\n== HIDDEN / INVESTMENT detail")
        all.filter { it.kind != "SPEND" }.forEach {
            out.appendLine("${it.kind} | ${it.orig_amount_minor / 100} | ${it.counterparty_raw} | ${it.body?.replace('\n', ' ')?.take(110)}")
        }
        out.appendLine("\n== UNTAGGED payees (count)")
        all.filter { it.category_id == null && it.kind == "SPEND" }.groupingBy { it.counterparty_raw ?: "∅" }.eachCount()
            .entries.sortedByDescending { it.value }.forEach { out.appendLine("${it.value}  ${it.key}") }
        File(root, "testdata/private/report.txt").writeText(out.toString())
        println(out.lines().take(3).joinToString("\n"))
    }

    private fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(s[i + 1])
                }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}
