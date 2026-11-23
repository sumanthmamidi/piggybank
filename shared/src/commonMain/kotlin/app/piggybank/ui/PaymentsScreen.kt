package app.piggybank.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.piggybank.core.YearMonth
import app.piggybank.month.MonthService
import app.piggybank.payments.PaymentGroup
import app.piggybank.payments.PaymentSort
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun currentMonth(): YearMonth =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.let { YearMonth(it.year, it.monthNumber) }

/** Every tagged payment, newest or biggest first, filtered by month, category and type. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PaymentsScreen(s: UiState, c: AppController, modifier: Modifier, onBack: (() -> Unit)?) {
    val p = s.payments
    val f = p.filter
    var selected by remember { mutableStateOf<TxnItem?>(null) }
    var retagging by remember { mutableStateOf<TxnItem?>(null) }
    var pickCategory by remember { mutableStateOf(false) }
    if (onBack != null) BackHandler(onBack = onBack)

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (onBack != null) PageHeader("Payments", onBack) else Title("Payments")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Month: ‹ Nov 2026 › or All time.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (f.month != null) TextButton(onClick = { c.setPaymentsFilter(f.copy(month = MonthService.previous(f.month))) }) {
                        Text("‹", fontSize = 22.sp, color = Piggy.Ink)
                    }
                    Text(p.monthLabel, fontWeight = FontWeight.Bold, color = Piggy.Ink)
                    if (f.month != null) TextButton(onClick = { c.setPaymentsFilter(f.copy(month = f.month.next())) }) {
                        Text("›", fontSize = 22.sp, color = Piggy.Ink)
                    }
                    Spacer(Modifier.weight(1f))
                    Chip(if (f.month == null) "One month" else "All time", false) {
                        c.setPaymentsFilter(f.copy(month = if (f.month == null) currentMonth() else null))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaymentGroup.entries.forEach { g -> Chip(g.label, f.group == g) { c.setPaymentsFilter(f.copy(group = g)) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip((p.categoryName ?: "Any category") + " ▾", p.categoryName != null) { pickCategory = true }
                    if (p.categoryName != null) Chip("✕", false) { c.setPaymentsFilter(f.copy(categoryId = null)) }
                    Spacer(Modifier.weight(1f))
                    PiggyIcon(PiggyIcons.Sort, Piggy.Muted, 18.dp)
                    Chip("Latest", f.sort == PaymentSort.LATEST) { c.setPaymentsFilter(f.copy(sort = PaymentSort.LATEST)) }
                    Chip("Highest", f.sort == PaymentSort.HIGHEST) { c.setPaymentsFilter(f.copy(sort = PaymentSort.HIGHEST)) }
                }
                Text("${p.count} payment${if (p.count == 1) "" else "s"} · ${p.total}", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (p.rows.isEmpty()) item {
            Text("No tagged payments match. Untagged ones wait in Review.", color = Piggy.Muted, modifier = Modifier.padding(top = 12.dp))
        }
        items(p.rows, key = { it.id }) { t ->
            Column {
                t.dayHeader?.let { Text(it, color = Piggy.Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) }
                TxnRow(t, onClick = { selected = t })
            }
        }
    }

    selected?.let { t ->
        TxnDialog(t, s.home, onDismiss = { selected = null },
            onRefund = { c.markRefund(t.id, it); selected = null },
            onChange = { retagging = t; selected = null },
            onCountIt = { c.countIt(t.id); selected = null },
            onOpenMap = { c.openMap(t) },
            onRemovePlace = { c.removePlace(t.id); selected = null })
    }
    retagging?.let { t -> ChangeTagDialog(t, s.categories, { retagging = null }) { k, cat, nw -> c.retag(t.id, k, cat, nw); retagging = null } }
    if (pickCategory) CategoryPicker(s.categories, f.categoryId, { pickCategory = false }) { id ->
        c.setPaymentsFilter(f.copy(categoryId = id)); pickCategory = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(categories: List<CategoryOption>, current: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Any category", current == null) { onPick(null) }
                listOf("SPENDS" to "Spends", "INVESTMENTS" to "Investments", "EXCLUDED" to "Hidden").forEach { (level, label) ->
                    Text(label, color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.filter { it.topLevel == level }.forEach { cat -> Chip(cat.name, current == cat.id) { onPick(cat.id) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Piggy.Muted) } },
        containerColor = Piggy.Card,
    )
}

/** Change a tagged payment's kind, category and Need/Want right here; no rule is saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChangeTagDialog(t: TxnItem, categories: List<CategoryOption>, onDismiss: () -> Unit, onSave: (String, Long?, String?) -> Unit) {
    var kind by remember { mutableStateOf(t.kind) }
    var categoryId by remember { mutableStateOf(t.categoryId) }
    var needWant by remember { mutableStateOf(t.needWant) }
    val topLevel = when (kind) { "SPEND" -> "SPENDS"; "INVESTMENT" -> "INVESTMENTS"; else -> null }
    val ok = when (kind) {
        "SPEND" -> categoryId != null && needWant != null
        "INVESTMENT" -> categoryId != null
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change tag") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${t.title} · ${t.amount}", color = Piggy.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KINDS.forEach { k ->
                        Chip(AppController.kindLabel(k), kind == k) {
                            kind = k
                            categoryId = when (k) {
                                "SPEND" -> categoryId?.takeIf { id -> categories.any { it.id == id && it.topLevel == "SPENDS" } }
                                "INVESTMENT" -> categoryId?.takeIf { id -> categories.any { it.id == id && it.topLevel == "INVESTMENTS" } }
                                else -> categories.firstOrNull { it.name == EXCLUDED_CATEGORY[k] }?.id
                            }
                        }
                    }
                }
                if (topLevel != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.topLevel == topLevel }.forEach { cat ->
                        Chip(cat.name, categoryId == cat.id, color = if (kind == "INVESTMENT") Piggy.Invest else Piggy.Ink) {
                            categoryId = cat.id
                            if (kind == "SPEND") needWant = cat.defaultNeedWant ?: needWant
                        }
                    }
                }
                if (kind == "SPEND") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("This is a", color = Piggy.Muted)
                    Chip("Need", needWant == "NEED", Piggy.Needs) { needWant = "NEED" }
                    Chip("Want", needWant == "WANT", Piggy.Wants) { needWant = "WANT" }
                }
                Text("Only this payment changes. Rules stay as they are.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onSave(kind, categoryId, if (kind == "SPEND") needWant else null) }) {
                Text("Save", color = if (ok) Piggy.Ink else Piggy.Line, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Piggy.Muted) } },
        containerColor = Piggy.Card,
    )
}
