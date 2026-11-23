package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.piggybank.core.Currency
import app.piggybank.core.Money
import app.piggybank.sms.SmsIngestor

@Composable
fun ReviewScreen(s: UiState, c: AppController, section: Int, onSection: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Close your day", if (s.streak > 0) "${s.streak}-day streak" else "One card per payee. Tag once, and it's remembered.")
        Segments(
            listOf("Today ${s.queueCount}", "Catch-up ${s.catchUpCount}", "SMS ${s.candidates.size}"),
            section, onSection,
        )
        when (section) {
            ReviewSection.TODAY -> TodayReview(s, c)
            ReviewSection.CATCH_UP -> CardStack(s.catchUp, s, c, empty = "Catch-up is done. Every imported payment is tagged.")
            else -> Candidates(s, c)
        }
    }
}

@Composable
private fun TodayReview(s: UiState, c: AppController) {
    if (s.queue.isNotEmpty()) {
        CardStack(s.queue, s, c, empty = "")
    } else if (s.dayClosed) {
        PiggyCard(dark = true) {
            Text("Day closed ✓", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${s.streak}-day streak", color = Piggy.Wants, fontWeight = FontWeight.Bold)
            Text("Spent today ${s.spentToday} · invested ${s.invested}", color = Color(0xFFB5B9C3))
        }
    } else {
        PiggyCard {
            Text("All caught up", fontWeight = FontWeight.Bold, color = Piggy.Ink)
            Text("Nothing left to tag today. Close the day to keep your streak.", color = Piggy.Muted)
            PrimaryButton("Close the day", Modifier.fillMaxWidth()) { c.closeDay() }
        }
    }
    if (s.autoTaggedToday.isNotEmpty()) {
        Section("Auto-tagged today (${s.autoTaggedToday.size})")
        Text("Tagged by your rules or recognised as transfers. Tap Change to fix one.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
        s.autoTaggedToday.forEach { t ->
            TxnRow(t, trailing = { TextButton(onClick = { c.reopen(t.id) }) { Text("Change", color = Piggy.Accent) } })
        }
    }
}

/** Shows the first card; skipped payees move to the back of the stack. */
@Composable
private fun CardStack(groups: List<ReviewGroup>, s: UiState, c: AppController, empty: String) {
    val skipped = remember { mutableStateListOf<String>() }
    val ordered = groups.sortedBy { if (it.key in skipped) 1 else 0 }
    val current = ordered.firstOrNull()
    if (current == null) {
        if (empty.isNotEmpty()) Text(empty, color = Piggy.Muted)
        return
    }
    Text("${ordered.size} payee${if (ordered.size == 1) "" else "s"} left · ${ordered.sumOf { it.txns.size }} payments", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
    ReviewCard(
        g = current,
        categories = s.categories,
        home = s.home,
        onConfirm = { kind, cat, nw, remember, rate -> c.confirm(current, kind, cat, nw, remember, rate) },
        onNotPayment = { c.notAPayment(current) },
        onSkip = { skipped.remove(current.key); skipped.add(current.key) },
        onDuplicate = { c.markDuplicate(it) },
        onSeparate = { c.separatePayments(it) },
    )
}

private val KINDS = listOf("SPEND", "INVESTMENT", "SELF_TRANSFER", "CC_BILL", "WALLET_TOPUP")
private val EXCLUDED_CATEGORY = mapOf("SELF_TRANSFER" to "Self-transfer", "CC_BILL" to "Card bill payment", "WALLET_TOPUP" to "Wallet top-up")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(
    g: ReviewGroup,
    categories: List<CategoryOption>,
    home: Currency,
    onConfirm: (kind: String, categoryId: Long?, needWant: String?, remember: Boolean, rate: String?) -> Unit,
    onNotPayment: () -> Unit,
    onSkip: () -> Unit,
    onDuplicate: (DuplicateHint) -> Unit,
    onSeparate: (DuplicateHint) -> Unit,
) {
    var kind by remember(g.key) { mutableStateOf(g.kind) }
    var categoryId by remember(g.key) { mutableStateOf(g.categoryId) }
    var needWant by remember(g.key) { mutableStateOf(g.needWant ?: categories.firstOrNull { it.id == g.categoryId }?.defaultNeedWant) }
    var rememberIt by remember(g.key) { mutableStateOf(!g.isPerson) }
    var showSms by remember(g.key) { mutableStateOf(false) }

    PiggyCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(g.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text(g.subtitle, color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(g.total, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Piggy.Ink)
        }
        g.txns.firstNotNullOfOrNull { it.fromLabel }?.let {
            Text(it, color = Piggy.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
        if (g.txns.size > 1) {
            Column(Modifier.background(Piggy.Ground, RoundedCornerShape(10.dp)).padding(8.dp)) {
                g.txns.take(4).forEach { t ->
                    Row { Text(t.whenLabel, color = Piggy.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(t.amount, fontSize = 13.sp, color = Piggy.Ink) }
                }
                if (g.txns.size > 4) Text("and ${g.txns.size - 4} more", color = Piggy.Muted, fontSize = 13.sp)
            }
        }

        g.duplicate?.let { d ->
            Column(Modifier.fillMaxWidth().background(Color(0xFFFFF1E0), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Possible duplicate", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text(d.label, color = Piggy.Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Duplicate") { onDuplicate(d) }
                    SecondaryButton("Separate payment") { onSeparate(d) }
                }
            }
        }

        g.suggestion?.let {
            Text("Possible self-transfer: $it. It counts as a spend until you mark it.", color = Piggy.Needs, style = MaterialTheme.typography.bodySmall)
        } ?: run {
            if (g.guessed && categoryId != null) Text("Suggested · check and confirm", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KINDS.forEach { k ->
                Chip(AppController.kindLabel(k), kind == k, color = kindColor(k, "NEED").takeIf { k != "SPEND" } ?: Piggy.Ink) {
                    kind = k
                    categoryId = when (k) {
                        "SPEND" -> categoryId?.takeIf { id -> categories.any { it.id == id && it.topLevel == "SPENDS" } }
                        "INVESTMENT" -> categoryId?.takeIf { id -> categories.any { it.id == id && it.topLevel == "INVESTMENTS" } }
                        else -> categories.firstOrNull { it.name == EXCLUDED_CATEGORY[k] }?.id
                    }
                }
            }
        }

        val topLevel = when (kind) { "SPEND" -> "SPENDS"; "INVESTMENT" -> "INVESTMENTS"; else -> null }
        if (topLevel != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.filter { it.topLevel == topLevel }.forEach { cat ->
                    Chip(cat.name, categoryId == cat.id, color = if (kind == "INVESTMENT") Piggy.Invest else Piggy.Ink) {
                        categoryId = cat.id
                        if (kind == "SPEND") needWant = cat.defaultNeedWant ?: needWant
                    }
                }
            }
        }
        // E8: Need/Want only for spends.
        if (kind == "SPEND") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("This is a", color = Piggy.Muted)
                Chip("Need", needWant == "NEED", Piggy.Needs) { needWant = "NEED" }
                Chip("Want", needWant == "WANT", Piggy.Wants) { needWant = "WANT" }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always tag ${g.title} like this", color = Piggy.Ink, fontWeight = FontWeight.Medium)
                if (g.isPerson) Text("Looks like a person, so it's off by default", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(rememberIt, { rememberIt = it }, colors = SwitchDefaults.colors(checkedTrackColor = Piggy.Ink))
        }

        // M2/M3: a foreign payment without a ₹ amount needs a rate before it can be confirmed.
        val foreign = g.foreign
        var rate by remember(g.key) { mutableStateOf(foreign?.savedRate ?: "") }
        val converted = foreign?.let { f -> SmsIngestor.convert(Money(f.originalMinor, Currency.of(f.currency)), rate, home)?.takeIf { rate.isNotBlank() && it.minor > 0 } }
        if (foreign != null && foreign.needsRate) {
            Column(Modifier.fillMaxWidth().background(Piggy.Ground, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Paid in ${foreign.currency}. Add the rate to count it in ${home.code}.", color = Piggy.Ink, fontWeight = FontWeight.Medium)
                // Shown above the field so it stays visible while the keyboard is open.
                Text(
                    converted?.let { "${Money(foreign.originalMinor, Currency.of(foreign.currency)).format(compact = true)} = ${it.format(compact = true)} · saved for future ${foreign.currency} payments" }
                        ?: "Use the rate on your card statement. It's never fetched online.",
                    color = if (converted != null) Piggy.Invest else Piggy.Muted,
                    fontWeight = if (converted != null) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("1 ${foreign.currency} = ${home.symbol}", color = Piggy.Ink)
                    AmountField("Rate", rate, { rate = it }, Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
        val rateOk = foreign == null || !foreign.needsRate || converted != null
        val ready = rateOk && when (kind) { "SPEND" -> categoryId != null && needWant != null; "INVESTMENT" -> categoryId != null; else -> true }
        PrimaryButton(if (g.txns.size > 1) "Confirm all ${g.txns.size}" else "Confirm", Modifier.fillMaxWidth(), enabled = ready) {
            onConfirm(kind, categoryId, needWant, rememberIt, rate.takeIf { foreign?.needsRate == true })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip) { Text("Skip for now", color = Piggy.Muted) }
            Spacer(Modifier.weight(1f))
            if (g.txns.first().body != null) TextButton(onClick = { showSms = !showSms }) { Text(if (showSms) "Hide SMS" else "Show SMS", color = Piggy.Muted) }
            TextButton(onClick = onNotPayment) { Text("Not a payment", color = Piggy.Accent) }
        }
        if (showSms) g.txns.first().body?.let {
            Text(it, color = Piggy.Ink, fontSize = 13.sp, modifier = Modifier.background(Piggy.Ground, RoundedCornerShape(10.dp)).padding(10.dp))
        }
    }
}

/** F5 / C2: SMS with an amount that weren't read as a payment. */
@Composable
private fun Candidates(s: UiState, c: AppController) {
    Text(
        "These mention an amount but didn't read as a finished payment. \"Not money\" hides every SMS in the same format.",
        color = Piggy.Muted, style = MaterialTheme.typography.bodySmall,
    )
    if (s.candidates.isEmpty()) Text("Nothing to check.", color = Piggy.Muted)
    s.candidates.take(60).forEach { sms ->
        PiggyCard {
            Text("${sms.sender} · ${sms.whenLabel}", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            Text(sms.body, color = Piggy.Ink, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("It's a payment") { c.pickCandidate(sms.id) }
                SecondaryButton("Not money") { c.notMoney(sms.id) }
            }
        }
    }
    if (s.candidates.size > 60) Text("Showing the newest 60 of ${s.candidates.size}.", color = Piggy.Muted)
}
