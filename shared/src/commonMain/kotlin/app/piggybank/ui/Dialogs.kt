package app.piggybank.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.piggybank.core.Currency
import app.piggybank.core.Income
import app.piggybank.core.IncomeSchedule
import app.piggybank.core.Money
import app.piggybank.core.YearMonth
import app.piggybank.month.IncomeLine
import app.piggybank.month.MonthService
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun thisMonth(): YearMonth =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.let { YearMonth(it.year, it.monthNumber) }

@Composable
internal fun AmountField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun MonthStepper(label: String, value: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Piggy.Muted, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange(MonthService.previous(value)) }) { Text("‹", color = Piggy.Ink) }
        Text("${AppController.MONTHS[value.month - 1]} ${value.year}", fontWeight = FontWeight.Bold, color = Piggy.Ink)
        TextButton(onClick = { onChange(value.next()) }) { Text("›", color = Piggy.Ink) }
    }
}

/** D7–D7d: salary or any other income, with a schedule and the months it applies to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncomeDialog(
    existing: IncomeLine?,
    home: Currency,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (IncomeLine) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "Salary") }
    var amount by remember { mutableStateOf(existing?.let { Money(it.amountMinor, home).toPlainString() } ?: "") }
    var schedule by remember { mutableStateOf(existing?.schedule ?: IncomeSchedule.MONTHLY) }
    var from by remember { mutableStateOf(existing?.from ?: thisMonth()) }
    var to by remember { mutableStateOf(existing?.to) }
    var mask by remember { mutableStateOf(existing?.monthsMask ?: Income.ALL_MONTHS) }
    val parsed = Money.parse(amount, home)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add income" else "Edit income") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField("Amount (${home.code})", amount, { amount = it })
                if (existing != null) Text("A new amount applies from the month you're viewing; earlier months keep the old one.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Every month", schedule == IncomeSchedule.MONTHLY) { schedule = IncomeSchedule.MONTHLY }
                    Chip("One-off", schedule == IncomeSchedule.ONE_OFF) { schedule = IncomeSchedule.ONE_OFF }
                }
                MonthStepper(if (schedule == IncomeSchedule.ONE_OFF) "In" else "From", from) { from = it }
                if (schedule == IncomeSchedule.MONTHLY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(to == null, { open -> to = if (open) null else from.next() }, colors = CheckboxDefaults.colors(checkedColor = Piggy.Ink))
                        Text("No end date", color = Piggy.Ink)
                    }
                    to?.let { t -> MonthStepper("To", t) { to = if (it < from) from else it } }
                    Text("Months it applies to", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppController.MONTHS.forEachIndexed { i, label ->
                            Chip(label, (mask shr i) and 1 == 1) { mask = mask xor (1 shl i) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ok = parsed != null && name.isNotBlank() && mask != 0
            TextButton(enabled = ok, onClick = {
                onSave(IncomeLine(existing?.id ?: 0, name.trim(), parsed!!.minor, schedule, from,
                    if (schedule == IncomeSchedule.MONTHLY) to else null, if (schedule == IncomeSchedule.MONTHLY) mask else Income.ALL_MONTHS))
            }) { Text("Save", color = if (ok) Piggy.Ink else Piggy.Line, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = Piggy.Accent) } }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Piggy.Muted) }
            }
        },
        containerColor = Piggy.Card,
    )
}

/** Budgets per spend category for the viewed month (later months inherit them), and which are fixed. */
@Composable
fun BudgetDialog(s: UiState, onDismiss: () -> Unit, onSave: (Map<Long, Long>, Set<Long>) -> Unit) {
    val home = s.home
    val cats = s.categories.filter { it.topLevel == "SPENDS" }
    val values = remember { mutableStateMapOf<Long, String>().apply { cats.forEach { put(it.id, s.month.budgets[it.id]?.let { v -> Money(v, home).toPlainString().removeSuffix(".00") } ?: "") } } }
    val fixed = remember { mutableStateMapOf<Long, Boolean>().apply { cats.forEach { put(it.id, it.id in s.month.fixed) } } }
    val total = cats.sumOf { Money.parse(values[it.id] ?: "", home)?.minor ?: 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budgets from ${s.month.label}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Total ${home.fmt(total)}. Fixed costs (paid once, like rent) are left out of the daily allowance.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                cats.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AmountField(cat.name, values[cat.id] ?: "", { values[cat.id] = it }, Modifier.weight(1f))
                        Checkbox(fixed[cat.id] == true, { fixed[cat.id] = it }, colors = CheckboxDefaults.colors(checkedColor = Piggy.Ink))
                        Text("Fixed", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(cats.associate { it.id to (Money.parse(values[it.id] ?: "", home)?.minor ?: 0) }, fixed.filterValues { it }.keys)
            }) { Text("Save", color = Piggy.Ink, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Piggy.Muted) } },
        containerColor = Piggy.Card,
    )
}

/** M3: "1 EUR = ₹90.20", typed in once and reused. */
@Composable
fun RateDialog(currency: String, current: String?, home: Currency, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var rate by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("1 $currency = ? ${home.code}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("Rate in ${home.code}", rate, { rate = it })
                Text("Fills every $currency payment still missing a ${home.code} amount, and future ones. Rates are never fetched online.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            val ok = rate.toDoubleOrNull()?.let { it > 0 } == true
            TextButton(enabled = ok, onClick = { onSave(rate) }) {
                Text("Save", color = if (ok) Piggy.Ink else Piggy.Line, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Piggy.Muted) } },
        containerColor = Piggy.Card,
    )
}

/** Tapping a payment: mark a refund (B6), change its tag, or count a hidden one (D9). */
@Composable
fun TxnDialog(
    t: TxnItem, home: Currency, onDismiss: () -> Unit, onRefund: (Long) -> Unit, onChange: () -> Unit, onCountIt: () -> Unit,
    onOpenMap: () -> Unit = {}, onRemovePlace: () -> Unit = {},
) {
    var refund by remember { mutableStateOf("") }
    val parsed = Money.parse(refund, home)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${t.amount} · ${t.whenLabel}", color = Piggy.Ink)
                t.refunded?.let { Text("Refunded so far: $it", color = Piggy.Invest) }
                t.place?.let { p ->
                    Text("📍 ${p.lat.fmt5()}, ${p.lon.fmt5()}" + (p.accuracyM?.let { " · within ${it.toInt()} m" } ?: ""), color = Piggy.Ink)
                    Text("Where your phone was when the SMS arrived.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onOpenMap) { Text("Open in Maps", color = Piggy.Accent) }
                        TextButton(onClick = onRemovePlace) { Text("Remove", color = Piggy.Muted) }
                    }
                }
                if (t.kind == "SPEND") {
                    AmountField("Refund received (${home.code})", refund, { refund = it })
                    Text("Lowers this spend; it can't go below zero.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val ok = parsed != null && parsed.minor > 0
            if (t.kind == "SPEND") TextButton(enabled = ok, onClick = { onRefund(parsed!!.minor) }) {
                Text("Mark refund", color = if (ok) Piggy.Ink else Piggy.Line, fontWeight = FontWeight.Bold)
            }
            else TextButton(onClick = onCountIt) { Text("Count it as a spend", color = Piggy.Ink, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChange) { Text("Change tag", color = Piggy.Accent) }
                TextButton(onClick = onDismiss) { Text("Close", color = Piggy.Muted) }
            }
        },
        containerColor = Piggy.Card,
    )
}

/** A category the user is missing, added from a review card and picked right away. */
@Composable
fun NewCategoryDialog(topLevel: String, taken: (String) -> String?, onDismiss: () -> Unit, onSave: (name: String, needWant: String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var needWant by remember { mutableStateOf<String?>(null) }
    val spend = topLevel == "SPENDS"
    val clash = taken(name)
    val ok = name.isNotBlank() && clash == null && (!spend || needWant != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (spend) "New spend category" else "New investment category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if (it.length <= app.piggybank.month.MAX_CATEGORY_NAME) name = it },
                    label = { Text("Name") }, placeholder = { Text(if (spend) "e.g. Pets" else "e.g. Gold") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (clash != null) Text("That name is already used for ${if (clash == "SPENDS") "spends" else if (clash == "INVESTMENTS") "investments" else "hidden payments"}.",
                    color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                if (spend) {
                    Text("Usually a", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Need", needWant == "NEED", Piggy.Needs) { needWant = "NEED" }
                        Chip("Want", needWant == "WANT", Piggy.Wants) { needWant = "WANT" }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onSave(name.trim(), if (spend) needWant else null) }) {
                Text("Add", color = if (ok) Piggy.Ink else Piggy.Line, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Piggy.Muted) } },
        containerColor = Piggy.Card,
    )
}

/** 5 decimals ≈ 1 m, without platform number formatting. */
private fun Double.fmt5(): String {
    val scaled = kotlin.math.round(this * 100_000).toLong()
    val sign = if (scaled < 0) "-" else ""
    val a = kotlin.math.abs(scaled)
    return "$sign${a / 100_000}.${(a % 100_000).toString().padStart(5, '0')}"
}

/** J1–J4, J8: what restore found, before anything is replaced. */
@Composable
fun RestoreDialogs(s: UiState, c: AppController) {
    s.restoreOffer?.let { o ->
        AlertDialog(
            onDismissRequest = c::dismissRestore,
            title = { Text("Restore backup from ${o.date}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${o.payments} payments, ${o.sms} SMS and ${o.rules} rules. Everything on this phone is replaced by the backup, " +
                        "then the app restarts and reads any SMS that arrived after it.", color = Piggy.Ink)
                    if (o.skipped.isNotEmpty()) Text("Skipped (damaged): " + o.skipped.joinToString("; "),
                        color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = c::confirmRestore) { Text("Restore", color = Piggy.Ink, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = c::dismissRestore) { Text("Cancel", color = Piggy.Muted) } },
            containerColor = Piggy.Card,
        )
    }
    s.restoreMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = c::dismissRestore,
            title = { Text("Couldn't restore") },
            text = { Text("$msg\n\nNothing on this phone was changed.", color = Piggy.Ink) },
            confirmButton = { TextButton(onClick = c::dismissRestore) { Text("OK", color = Piggy.Ink, fontWeight = FontWeight.Bold) } },
            containerColor = Piggy.Card,
        )
    }
}
