package app.piggybank.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.piggybank.core.Currency
import app.piggybank.core.IncomeSchedule
import app.piggybank.core.Money
import app.piggybank.core.YearMonth
import app.piggybank.month.IncomeLine
import app.piggybank.month.MonthReport
import app.piggybank.month.MonthService
import app.piggybank.db.PiggyDatabase
import app.piggybank.db.SelectReviewQueue
import app.piggybank.review.Classifier
import app.piggybank.review.ReviewService
import app.piggybank.sms.IngestSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** What only the platform can do: permissions and reading the phone's SMS inbox. */
interface Platform {
    fun hasSmsPermission(): Boolean
    val smsDenied: Boolean
    fun requestSmsAccess()
    fun openAppSettings()
    fun importDone(): Boolean
    /** Blocking; called off the main thread. */
    fun importHistory(months: Int): IngestSummary
    /** Blocking; null when SMS reading isn't set up. */
    fun catchUp(): IngestSummary?
}

enum class SmsAccess { NOT_ASKED, DENIED, GRANTED_NO_IMPORT, READY }

data class AppStatus(val categoryCount: Long, val smsCount: Long, val homeCurrency: String)

data class CategoryOption(val id: Long, val name: String, val topLevel: String, val defaultNeedWant: String?)

data class TxnItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val amount: String,
    val kind: String,
    val category: String?,
    val needWant: String?,
    val whenLabel: String,
    val auto: Boolean,
    val refunded: String? = null,
)

data class ReviewTxn(val id: Long, val amount: String, val whenLabel: String, val fromLabel: String?, val body: String?)

data class DuplicateHint(val txnId: Long, val otherId: Long, val label: String)

data class ReviewGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val total: String,
    val isPerson: Boolean,
    val txns: List<ReviewTxn>,
    val kind: String,
    val categoryId: Long?,
    val needWant: String?,
    val guessed: Boolean,
    val duplicate: DuplicateHint?,
    /** e.g. "Money arrived in your account ••2222": why [kind] is pre-selected. */
    val suggestion: String? = null,
    /** Set when the payments are in a foreign currency (a group never mixes currencies). */
    val foreign: ForeignAmount? = null,
)

data class SmallPayment(val id: Long, val homeMinor: Long)

/** A foreign-currency group: its original total, and whether a ₹ rate is still needed (M2). */
data class ForeignAmount(
    val currency: String,
    val originalMinor: Long,
    val needsRate: Boolean,
    /** The user's last rate for this currency, to pre-fill. */
    val savedRate: String?,
)

data class SmsRow(val id: Long, val whenLabel: String, val sender: String, val body: String)

data class RuleRow(val id: Long, val match: String, val result: String, val hits: Long, val alwaysAsk: Boolean)

/** The Monthly tab for the month being viewed (H9: any month, past ones read-only). */
data class MonthUi(
    val label: String,
    val shortName: String,
    val isCurrent: Boolean,
    val report: MonthReport,
    val incomes: List<IncomeLine>,
    val budgets: Map<Long, Long>,
    val fixed: Set<Long>,
    val rates: Map<String, String>,
    val needingRate: Map<String, Int>,
)

data class UiState(
    val home: Currency,
    val month: MonthUi,
    /** Today's allowance from the current month's budget (G3); null without a budget. */
    val allowanceToday: Long?,
    val spentTodayMinor: Long,
    val status: AppStatus,
    val access: SmsAccess,
    val syncing: Boolean,
    val lastResult: String?,
    val todayLabel: String,
    val spentToday: String,
    val needs: String,
    val wants: String,
    val untagged: String,
    val invested: String,
    val notCounted: List<TxnItem>,
    val todayItems: List<TxnItem>,
    val queue: List<ReviewGroup>,
    val queueCount: Int,
    val catchUp: List<ReviewGroup>,
    val catchUpCount: Int,
    /** Catch-up payments already identified that "Tag all identified" would confirm. */
    val catchUpIdentified: Int,
    /** Catch-up payments "Tag everything under" may take, with their home amounts (no transfer/duplicate question). */
    val catchUpSmall: List<SmallPayment>,
    /** How many months of SMS history catch-up covers; 0 after clearing, null before the first import. */
    val historyMonths: Int?,
    /** Untagged payments each shorter period would remove, by months (0 = clear all). */
    val periodDrops: Map<Int, Int>,
    val autoTaggedToday: List<TxnItem>,
    val candidates: List<SmsRow>,
    val dayClosed: Boolean,
    val streak: Long,
    val categories: List<CategoryOption>,
    val rules: List<RuleRow>,
    val autoRate: Int?,
    val ownAccounts: List<String>,
    val duplicates: List<TxnItem>,
    val formats: String,
)

/**
 * Holds the screen state and runs every action off the main thread, one at a time.
 * Shared by Android and (later) iOS; only [Platform] differs.
 */
class AppController(
    private val db: PiggyDatabase,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val tz: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    var state by mutableStateOf<UiState?>(null)
        private set

    private val lock = Mutex()
    private var syncing = false
    private var lastResult: String? = null
    private var classifiedOnce = false

    private val review get() = ReviewService(db, tz())
    private val months = MonthService(db)
    private var viewedMonth: YearMonth? = null

    fun refresh() = act { }

    /** Runs [work] on a background thread, then reloads the state. */
    private fun act(work: () -> Unit) {
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                lock.withLock {
                    work()
                    load()
                }
            }
            state = next
        }
    }

    // ---- SMS ----

    fun requestSmsAccess() = platform.requestSmsAccess()
    fun openAppSettings() = platform.openAppSettings()
    fun importHistory(months: Int) = scan { platform.importHistory(months) }
    fun syncNow() = scan { platform.catchUp() }

    private fun scan(block: () -> IngestSummary?) {
        if (syncing) return
        syncing = true
        state = state?.copy(syncing = true)
        act {
            val r = runCatching(block)
            syncing = false
            r.getOrNull()?.let { s ->
                Classifier(db).run()
                val new = s.read - s.alreadyStored - s.notMoney
                lastResult = if (new == 0) "Up to date. No new money SMS."
                else "Last check: ${s.payments} new payments, ${s.candidates} to check."
            }
            r.exceptionOrNull()?.let { lastResult = "Couldn't read the inbox: ${it.message}" }
        }
    }

    // ---- Review ----

    /** [rate] is "1 <foreign> = rate <home>" typed on the card; it's saved and fills every waiting payment in that currency. */
    fun confirm(group: ReviewGroup, kind: String, categoryId: Long?, needWant: String?, remember: Boolean, rate: String? = null) = act {
        if (rate != null && group.foreign != null) months.setRate(group.foreign.currency, rate)
        review.tag(group.txns.map { it.id }, kind, categoryId, needWant, remember)
    }

    /** Confirms every identified catch-up guess as it stands, without saving rules for future payments. */
    fun tagAllIdentified() = act {
        review.acceptGuesses(state?.catchUp?.flatMap { g -> g.txns.map { it.id } } ?: emptyList())
    }

    /** Adds a category from a review card; [onAdded] gets its id (or null if the name is taken elsewhere) once the state has it. */
    fun addCategory(name: String, topLevel: String, needWant: String?, onAdded: (Long?) -> Unit) {
        scope.launch {
            val (id, next) = withContext(Dispatchers.Default) {
                lock.withLock { months.addCategory(name, topLevel, needWant) to load() }
            }
            state = next
            onAdded(id)
        }
    }

    /** Tags every catch-up payment under [underMinor] as [categoryId], without saving a rule. */
    fun tagSmallPayments(underMinor: Long, categoryId: Long, needWant: String?) = act {
        review.tagSmallPayments(state?.catchUpSmall?.map { it.id } ?: emptyList(), underMinor, categoryId, needWant)
    }

    /**
     * Changes how far back catch-up goes. Longer reads older SMS from the inbox; shorter (or 0, clear)
     * removes untagged payments from before the new start. Tagged payments always stay.
     */
    fun setCatchUpPeriod(months: Int) {
        val current = historyMonths() ?: return
        if (months > current) return importHistory(months)
        act {
            review.dropUntaggedArrivedBefore(periodStart(months, reviewFrom()))
            db.appQueries.putMeta(HISTORY_MONTHS, months.toString())
        }
    }

    private fun historyMonths() = db.appQueries.getMeta(HISTORY_MONTHS).executeAsOneOrNull()?.toIntOrNull()

    private fun reviewFrom(): Long = db.appQueries.getMeta(REVIEW_FROM).executeAsOneOrNull()?.toLongOrNull()
        ?.let { startOf(Instant.fromEpochMilliseconds(it).toLocalDateTime(tz()).date) } ?: Long.MAX_VALUE

    /** Where a catch-up period of [months] starts; never later than where the daily review starts. */
    private fun periodStart(months: Int, reviewFrom: Long): Long =
        if (months == 0) reviewFrom
        else minOf(reviewFrom, Clock.System.now().minus(DateTimePeriod(months = months), tz()).toEpochMilliseconds())

    fun notAPayment(group: ReviewGroup) = act { group.txns.forEach { review.notAPayment(it.id) } }
    fun markDuplicate(hint: DuplicateHint) = act { review.markDuplicate(hint.txnId, hint.otherId) }
    fun separatePayments(hint: DuplicateHint) = act { review.separatePayments(hint.txnId, hint.otherId) }
    fun undoDuplicate(id: Long) = act { review.undoDuplicate(id) }
    fun countIt(id: Long) = act { review.countIt(id) }
    fun reopen(id: Long) = act { review.reopen(id) }
    fun pickCandidate(smsId: Long) = act { review.pickCandidate(smsId) }
    fun notMoney(smsId: Long) = act { review.notMoney(smsId) }
    fun closeDay() = act {
        val today = today()
        review.closeDay(today, dayItems(today).size)
    }

    // ---- Month, income, budgets, rates, refunds (M3) ----

    fun showMonth(delta: Int) = act {
        var m = viewedMonth ?: currentMonth()
        repeat(kotlin.math.abs(delta)) { m = if (delta > 0) m.next() else MonthService.previous(m) }
        viewedMonth = m
    }

    fun addIncome(name: String, amountMinor: Long, schedule: IncomeSchedule, from: YearMonth, to: YearMonth?, mask: Int) =
        act { months.addIncome(name, amountMinor, schedule, from, to, mask) }

    /** D7c: a changed amount applies from the viewed month on; earlier months keep the old one. */
    fun updateIncome(old: IncomeLine, new: IncomeLine) = act {
        val from = viewedMonth ?: currentMonth()
        if (new.amountMinor != old.amountMinor && from > old.from &&
            new.copy(amountMinor = old.amountMinor) == old
        ) months.changeAmountFrom(old, from, new.amountMinor)
        else months.updateIncome(new)
    }

    fun removeIncome(id: Long) = act { months.removeIncome(id) }

    fun saveBudgets(amounts: Map<Long, Long>, fixed: Set<Long>) = act {
        months.saveBudgets(viewedMonth ?: currentMonth(), amounts)
        months.setFixed(fixed)
    }

    fun setRate(currency: String, rate: String) = act { months.setRate(currency, rate) }
    fun markRefund(txnId: Long, minor: Long) = act { months.markRefund(txnId, minor) }

    private fun currentMonth() = today().let { YearMonth(it.year, it.monthNumber) }

    // ---- Rules ----

    fun deleteRule(id: Long) = act { review.deleteRule(id) }
    fun setAlwaysAsk(id: Long, on: Boolean) = act { review.setAlwaysAsk(id, on) }

    // ---- Loading ----

    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun today(): LocalDate = Clock.System.now().toLocalDateTime(tz()).date
    private fun startOf(d: LocalDate) = d.atStartOfDayIn(tz()).toEpochMilliseconds()
    private fun dayItems(d: LocalDate) =
        db.txnQueries.selectWithDetailsBetween(startOf(d), startOf(d.plus(DatePeriod(days = 1)))).executeAsList()

    private fun load(): UiState {
        val zone = tz()
        val home = Currency.of(db.appQueries.getSetting("home_currency").executeAsOneOrNull() ?: "INR")
        fun money(minor: Long?) = minor?.let { Money(it, home).format(compact = true) } ?: "₹ amount needed"

        val access = when {
            !platform.hasSmsPermission() -> if (platform.smsDenied) SmsAccess.DENIED else SmsAccess.NOT_ASKED
            !platform.importDone() -> SmsAccess.GRANTED_NO_IMPORT
            else -> SmsAccess.READY
        }
        // Payments whose SMS arrived before this point were imported history: they go to catch-up,
        // never into a daily review (A7).
        if (access == SmsAccess.READY && db.appQueries.getMeta(REVIEW_FROM).executeAsOneOrNull() == null) {
            db.appQueries.putMeta(REVIEW_FROM, now().toString())
        }
        if (!classifiedOnce) {
            Classifier(db).run()
            classifiedOnce = true
        }
        // The daily review starts at the beginning of the import day, so that day's payments are reviewed as "today".
        val reviewFrom = db.appQueries.getMeta(REVIEW_FROM).executeAsOneOrNull()?.toLongOrNull()
            ?.let { startOf(Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date) } ?: Long.MAX_VALUE

        val today = today()
        val todayStart = startOf(today)
        val tomorrowStart = startOf(today.plus(DatePeriod(days = 1)))
        val dayRows = dayItems(today).filter { it.duplicate_of == null }

        fun item(id: Long, counterparty: String?, institution: String?, last4: String?, orig: Long, cur: String,
                 homeMinor: Long?, kind: String, category: String?, nw: String?, at: Long, source: String?, refund: Long = 0) = TxnItem(
            id = id,
            title = counterparty ?: "Unknown payee",
            subtitle = listOfNotNull(institution, last4?.let { "••$it" }).joinToString(" "),
            amount = if (cur == home.code) money(orig) else "${Money(orig, Currency.of(cur)).format(compact = true)} · ${money(homeMinor)}",
            kind = kind,
            category = category,
            needWant = nw,
            whenLabel = whenLabel(at, zone, today),
            auto = source == "RULE",
            refunded = refund.takeIf { it > 0 }?.let { money(it) },
        )

        val counted = dayRows.filter { it.kind == "SPEND" && it.home_amount_minor != null }
        fun sum(rows: List<app.piggybank.db.SelectWithDetailsBetween>) = rows.sumOf { (it.home_amount_minor ?: 0) - it.refund_minor }
        val todayItems = dayRows.map {
            item(it.id, it.counterparty_raw, it.institution, it.last4, it.orig_amount_minor, it.orig_currency,
                it.home_amount_minor, it.kind, it.category_name, it.need_want, it.occurred_at, it.tag_source, it.refund_minor)
        }

        val savedRates = months.rates()
        val queueRows = db.txnQueries.selectReviewQueue(reviewFrom, Long.MAX_VALUE).executeAsList()
        val catchUpRows = db.txnQueries.selectReviewQueue(0, reviewFrom).executeAsList()

        val auto = db.txnQueries.selectAutoTaggedArrived(todayStart, tomorrowStart).executeAsList().map {
            item(it.id, it.counterparty_raw, it.institution, it.last4, it.orig_amount_minor, it.orig_currency,
                it.home_amount_minor, it.kind, it.category_name, it.need_want, it.occurred_at, it.tag_source)
        }

        val rules = db.ruleQueries.selectWithCategory().executeAsList().map { r ->
            RuleRow(
                id = r.id,
                match = if (r.field_ == "VPA") r.pattern else r.pattern.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                result = listOfNotNull(kindLabel(r.result_kind).takeIf { r.result_kind != "SPEND" }, r.category_name, r.result_need_want?.let { nwLabel(it) })
                    .joinToString(" · "),
                hits = r.hits,
                alwaysAsk = r.always_ask == 1L,
            )
        }
        val bySource = db.txnQueries.countReviewedBySource().executeAsList().associate { it.tag_source to it.n }
        val ruled = bySource["RULE"] ?: 0
        val manual = bySource["USER"] ?: 0
        val templates = db.smsQueries.selectTemplates().executeAsList().groupingBy { it.verdict }.eachCount()

        val current = currentMonth()
        val viewed = viewedMonth ?: current
        val currentReport = months.report(current, today, zone)
        val viewedReport = if (viewed == current) currentReport else months.report(viewed, today, zone)

        return UiState(
            home = home,
            month = MonthUi(
                label = "${FULL_MONTHS[viewed.month - 1]} ${viewed.year}",
                shortName = FULL_MONTHS[viewed.month - 1],
                isCurrent = viewed == current,
                report = viewedReport,
                incomes = months.incomes(),
                budgets = months.budgetsFor(viewed),
                fixed = months.fixedCategories(),
                rates = months.rates(),
                needingRate = months.currenciesNeedingRate(),
            ),
            allowanceToday = currentReport.allowanceToday,
            spentTodayMinor = currentReport.spentToday,
            status = AppStatus(
                categoryCount = db.categoryQueries.selectActive().executeAsList().size.toLong(),
                smsCount = db.smsQueries.countSms().executeAsOne(),
                homeCurrency = home.code,
            ),
            access = access,
            syncing = syncing,
            lastResult = lastResult,
            todayLabel = "${weekday(today)}, ${today.dayOfMonth} ${MONTHS[today.monthNumber - 1]}",
            spentToday = money(sum(counted)),
            // A suggestion isn't a tag: until it's confirmed (or a rule tagged it) it counts as untagged.
            needs = money(sum(counted.filter { it.reviewed_at != null && it.need_want == "NEED" })),
            wants = money(sum(counted.filter { it.reviewed_at != null && it.need_want == "WANT" })),
            untagged = money(sum(counted.filter { it.reviewed_at == null || it.need_want == null })),
            invested = money(sum(dayRows.filter { it.kind == "INVESTMENT" })),
            notCounted = todayItems.filter { it.kind in HIDDEN },
            todayItems = todayItems.filter { it.kind !in HIDDEN },
            queue = groups(queueRows, home, zone, today, withDuplicates = true, rates = savedRates),
            queueCount = queueRows.size,
            catchUp = groups(catchUpRows, home, zone, today, withDuplicates = true, showFrom = false, rates = savedRates),
            catchUpCount = catchUpRows.size,
            catchUpIdentified = review.identifiedGuesses(catchUpRows.map { it.id }).size,
            catchUpSmall = review.smallPayments(catchUpRows.map { it.id }, Long.MAX_VALUE).let { ok ->
                catchUpRows.filter { it.id in ok }.map { SmallPayment(it.id, it.home_amount_minor!!) }
            },
            historyMonths = historyMonths(),
            periodDrops = (listOf(0) + PERIODS).associateWith { review.untaggedArrivedBefore(periodStart(it, reviewFrom)) },
            autoTaggedToday = auto,
            candidates = db.smsQueries.selectCandidatesBetween(0, Long.MAX_VALUE).executeAsList().map {
                SmsRow(it.id, whenLabel(it.received_at, zone, today), it.sender, it.body)
            },
            dayClosed = db.appQueries.selectDay(today.toString()).executeAsOneOrNull() != null,
            streak = review.streak(today),
            categories = db.categoryQueries.selectActive().executeAsList().map {
                CategoryOption(it.id, it.name, it.top_level, it.default_need_want)
            },
            rules = rules,
            autoRate = if (ruled + manual == 0L) null else ((ruled * 100) / (ruled + manual)).toInt(),
            ownAccounts = db.accountQueries.selectAll().executeAsList().filter { it.is_own == 1L }.map {
                "${it.institution} ••${it.last4} · ${if (it.kind == "CARD") "card" else "account"}"
            },
            duplicates = db.txnQueries.selectDuplicates().executeAsList().map {
                item(it.id, it.counterparty_raw, null, null, it.orig_amount_minor, it.orig_currency, it.home_amount_minor,
                    it.kind, it.category_name, it.need_want, it.occurred_at, it.tag_source)
            },
            formats = "${templates["WHITELIST"] ?: 0} allowed · ${templates["BLACKLIST"] ?: 0} blocked · ${templates["PENDING"] ?: 0} not decided yet",
        )
    }

    /**
     * One card per payee, so 30 payments to the same shop are tagged in one tap. A payment that
     * may be a duplicate gets its own card with the question on it (A6a).
     */
    private val selfTransferCategory by lazy { db.categoryQueries.selectByName("Self-transfer").executeAsOneOrNull()?.id }

    private fun groups(rows: List<SelectReviewQueue>, home: Currency, zone: TimeZone, today: LocalDate, withDuplicates: Boolean, showFrom: Boolean = true, rates: Map<String, String> = emptyMap()): List<ReviewGroup> {
        val dupHints = if (withDuplicates) rows.mapNotNull { r ->
            db.txnQueries.selectPossibleDuplicate(r.id).executeAsOneOrNull()?.let { o ->
                r.id to DuplicateHint(r.id, o.id, "Same as ${o.counterparty_raw ?: "a payment"} ${o.home_amount_minor?.let { Money(it, home).format(compact = true) } ?: ""} at ${clock(o.occurred_at, zone)}?")
            }
        }.toMap() else emptyMap()

        return rows.groupBy { r ->
            // Foreign payments get their own card per currency, so one rate covers the whole card.
            (if (r.orig_currency != home.code) "${r.orig_currency}:" else "") + when {
                r.id in dupHints -> "dup:${r.id}"
                r.suggested_kind != null -> "suggest:${r.id}"
                r.vpa != null -> "vpa:${r.vpa.lowercase()}"
                else -> Classifier.nameKey(r.counterparty_raw, null)?.let { "name:$it" } ?: "txn:${r.id}"
            }
        }.map { (key, list) ->
            val first = list.first()
            val name = Classifier.nameKey(first.counterparty_raw, first.vpa)
            val total = list.sumOf { it.home_amount_minor ?: 0 }
            val foreignCur = first.orig_currency.takeIf { it != home.code }
            val foreign = foreignCur?.let { cur ->
                ForeignAmount(cur, list.sumOf { it.orig_amount_minor }, list.any { it.home_amount_minor == null }, rates[cur])
            }
            val days = list.map { Instant.fromEpochMilliseconds(it.occurred_at).toLocalDateTime(zone).date }
            ReviewGroup(
                key = key,
                title = first.counterparty_raw ?: "Unknown payee",
                subtitle = listOfNotNull(
                    listOfNotNull(first.institution, first.last4?.let { "••$it" }).joinToString(" ").ifEmpty { null },
                    if (list.size == 1) whenLabel(first.occurred_at, zone, today) else "${list.size} payments · ${dayRange(days.min(), days.max())}",
                ).joinToString(" · "),
                // Never "₹0" for a foreign payment without a rate: show what was actually paid.
                total = if (foreign != null)
                    Money(foreign.originalMinor, Currency.of(foreign.currency)).format(compact = true) +
                        if (foreign.needsRate) "" else " · ${Money(total, home).format(compact = true)}"
                else Money(total, home).format(compact = true),
                isPerson = Classifier.looksLikePerson(name, first.vpa),
                txns = list.map {
                    val d = Instant.fromEpochMilliseconds(it.occurred_at).toLocalDateTime(zone).date
                    ReviewTxn(
                        id = it.id,
                        amount = it.home_amount_minor?.let { m -> Money(m, home).format(compact = true) } ?: Money(it.orig_amount_minor, Currency.of(it.orig_currency)).format(compact = true),
                        whenLabel = whenLabel(it.occurred_at, zone, today),
                        // B7b: an older payment showing up in today's review says which day it belongs to.
                        fromLabel = if (showFrom && d < today) "From ${d.dayOfMonth} ${MONTHS[d.monthNumber - 1]}" else null,
                        body = it.body,
                    )
                },
                kind = first.suggested_kind ?: first.kind,
                categoryId = if (first.suggested_kind == "SELF_TRANSFER") selfTransferCategory else first.category_id,
                needWant = first.need_want,
                guessed = first.tag_source == "GUESS",
                duplicate = dupHints[first.id],
                suggestion = first.suggest_reason,
                foreign = foreign,
            )
        }.sortedWith(
            // Quick questions first (possible transfer, possible duplicate, a missing ₹ rate that keeps money
            // out of the totals), then the biggest payee groups.
            compareByDescending<ReviewGroup> { it.suggestion != null || it.duplicate != null || it.foreign?.needsRate == true }
                .thenByDescending { it.txns.size },
        )
    }

    companion object {
        const val REVIEW_FROM = "review_from"
        /** Written by the platform's history import; the same key. */
        const val HISTORY_MONTHS = "history_months"
        val PERIODS = listOf(1, 3, 6, 12)
        val HIDDEN = setOf("SELF_TRANSFER", "CC_BILL", "WALLET_TOPUP")
        val FULL_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December")
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        fun weekday(d: LocalDate) = DAYS[d.dayOfWeek.ordinal]
        fun kindLabel(kind: String) = when (kind) {
            "SPEND" -> "Spend"
            "INVESTMENT" -> "Investment"
            "SELF_TRANSFER" -> "Self-transfer"
            "CC_BILL" -> "Card bill"
            "WALLET_TOPUP" -> "Wallet top-up"
            else -> kind
        }
        fun nwLabel(nw: String) = if (nw == "NEED") "Need" else "Want"

        fun clock(ms: Long, zone: TimeZone): String {
            val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
            return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        }

        fun whenLabel(ms: Long, zone: TimeZone, today: LocalDate): String {
            val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
            val day = when (t.date) {
                today -> "Today"
                today.plus(DatePeriod(days = -1)) -> "Yesterday"
                else -> "${t.dayOfMonth} ${MONTHS[t.monthNumber - 1]}" + if (t.year != today.year) " ${t.year}" else ""
            }
            return "$day, ${clock(ms, zone)}"
        }

        fun dayRange(a: LocalDate, b: LocalDate): String =
            if (a == b) "${a.dayOfMonth} ${MONTHS[a.monthNumber - 1]}"
            else "${a.dayOfMonth} ${MONTHS[a.monthNumber - 1]} – ${b.dayOfMonth} ${MONTHS[b.monthNumber - 1]}"
    }
}
