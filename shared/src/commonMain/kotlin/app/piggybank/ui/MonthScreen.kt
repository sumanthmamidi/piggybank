package app.piggybank.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.piggybank.core.Currency
import app.piggybank.core.Money
import app.piggybank.month.CategoryLine
import app.piggybank.month.IncomeLine
import app.piggybank.month.MonthReport

fun Currency.fmt(minor: Long) = Money(minor, this).format(compact = true)

private val SubtleOnDark = Color(0xFFB5B9C3)

@Composable
fun MonthScreen(s: UiState, c: AppController, modifier: Modifier) {
    val m = s.month
    val r = m.report
    val home = s.home
    var editIncome by remember { mutableStateOf<IncomeLine?>(null) }
    var addIncome by remember { mutableStateOf(false) }
    var editBudgets by remember { mutableStateOf(false) }
    var rateFor by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // H9: browse months.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c.showMonth(-1) }) { Text("‹", fontSize = 26.sp, color = Piggy.Ink) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(m.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text(if (m.isCurrent) "Day ${r.daysSoFar} of ${r.daysInMonth}" else "Final numbers", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { c.showMonth(1) }) { Text("›", fontSize = 26.sp, color = Piggy.Ink) }
        }

        SavingsCard(r, home, m.shortName) { addIncome = true }
        BurnUpCard(r, home, m.isCurrent)
        BudgetsCard(r, home) { editBudgets = true }
        r.suggestion?.let { (to, from) ->
            PiggyCard {
                Text("Budget tip", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text("${to} is heading over budget. Move ${home.fmt(from.second)} from ${from.first}, which has room to spare?", color = Piggy.Muted)
            }
        }

        // Not counted, foreign money, rates.
        PiggyCard {
            Text("Not counted in spends", fontWeight = FontWeight.Bold, color = Piggy.Ink)
            Line("Self-transfers", home.fmt(r.selfTransfers))
            Line("Card bill payments", home.fmt(r.cardBills))
            if (r.walletTopUps > 0) Line("Wallet top-ups", home.fmt(r.walletTopUps))
            if (r.foreign.isNotEmpty()) {
                Text("Includes " + r.foreign.entries.joinToString(" + ") { Money(it.value, Currency.of(it.key)).format(compact = true) } + " in foreign spends",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (m.needingRate.isNotEmpty()) {
            PiggyCard {
                Text("₹ amount needed", fontWeight = FontWeight.Bold, color = Piggy.Accent)
                m.needingRate.forEach { (cur, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n $cur payment${if (n == 1) "" else "s"} left out of totals", color = Piggy.Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = { rateFor = cur }) { Text("Set rate", color = Piggy.Accent) }
                    }
                }
            }
        }

        Section("Income", "Add") { addIncome = true }
        if (m.incomes.isEmpty()) Text("No income yet. Add your salary and any other regular income.", color = Piggy.Muted)
        m.incomes.forEach { inc ->
            Row(Modifier.fillMaxWidth().background(Piggy.Card, RoundedCornerShape(14.dp)).clickable { editIncome = inc }.padding(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(inc.name, fontWeight = FontWeight.Bold, color = Piggy.Ink)
                    Text(incomeWhen(inc), color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(home.fmt(inc.amountMinor), fontWeight = FontWeight.Bold, color = Piggy.Ink)
            }
        }

        if (m.rates.isNotEmpty()) {
            Section("Exchange rates")
            PiggyCard {
                Text("Typed in by you. Never fetched from the internet.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                m.rates.forEach { (cur, rate) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1 $cur = ${home.symbol}$rate", color = Piggy.Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = { rateFor = cur }) { Text("Change", color = Piggy.Accent) }
                    }
                }
            }
        }
    }

    if (addIncome) IncomeDialog(null, home, onDismiss = { addIncome = false }) { line ->
        c.addIncome(line.name, line.amountMinor, line.schedule, line.from, line.to, line.monthsMask); addIncome = false
    }
    editIncome?.let { old ->
        IncomeDialog(old, home, onDismiss = { editIncome = null }, onDelete = { c.removeIncome(old.id); editIncome = null }) { line ->
            c.updateIncome(old, line); editIncome = null
        }
    }
    if (editBudgets) BudgetDialog(s, onDismiss = { editBudgets = false }) { amounts, fixed ->
        c.saveBudgets(amounts, fixed); editBudgets = false
    }
    rateFor?.let { cur ->
        RateDialog(cur, m.rates[cur], home, onDismiss = { rateFor = null }) { rate -> c.setRate(cur, rate); rateFor = null }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Piggy.Ink) {
    Row { Text(label, color = Piggy.Muted, modifier = Modifier.weight(1f)); Text(value, color = color, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SavingsCard(r: MonthReport, home: Currency, monthName: String, onAddIncome: () -> Unit) {
    PiggyCard(dark = true) {
        when {
            r.savings == null -> { // H2
                Text("Savings", color = SubtleOnDark)
                Text("Add your income for $monthName to see what you saved.", color = Color.White)
                PrimaryButton("Add income", Modifier.fillMaxWidth().background(Piggy.Accent, RoundedCornerShape(50))) { onAddIncome() }
            }
            r.savings < 0 -> { // H4
                Text("Overspent", color = SubtleOnDark)
                Text(home.fmt(-r.savings), color = Piggy.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            else -> { // H1
                Text("Saved", color = SubtleOnDark)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(home.fmt(r.savings), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("  ${r.savingsPct}% of income", color = Piggy.Invest, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
        r.incomeLines.forEach { (name, amt) -> DarkLine(name, home.fmt(amt)) } // H3
        // Where the income went: needs | wants | untagged | invested | still in bank.
        val parts = listOf(
            Triple("Needs", r.needs, Piggy.Needs), Triple("Wants", r.wants, Piggy.Wants), Triple("Untagged", r.untagged, Piggy.Hidden),
            Triple("Invested", r.invested, Piggy.Invest), Triple("Still in bank", (r.stillInBank ?: 0).coerceAtLeast(0), Color(0xFF3A3E48)),
        )
        val total = parts.sumOf { it.second }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF23262E), RoundedCornerShape(7.dp))) {
            parts.filter { it.second > 0 }.forEach { (_, v, col) ->
                Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(col))
            }
        }
        parts.filter { it.second > 0 }.forEach { (label, v, col) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(col, RoundedCornerShape(2.dp)))
                Text("  $label", color = SubtleOnDark, modifier = Modifier.weight(1f))
                Text(home.fmt(v), color = Color.White)
            }
        }
    }
}

@Composable
private fun DarkLine(label: String, value: String) {
    Row { Text(label, color = SubtleOnDark, modifier = Modifier.weight(1f)); Text(value, color = Color.White) }
}

/** Cumulative spend vs the budget's straight-line pace, with the month-end projection dotted (H7). */
@Composable
private fun BurnUpCard(r: MonthReport, home: Currency, isCurrent: Boolean) {
    PiggyCard {
        Text("Spent this month", fontWeight = FontWeight.Bold, color = Piggy.Ink)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(home.fmt(r.spends), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Piggy.Ink)
            if (r.budgetTotal > 0) Text("  of ${home.fmt(r.budgetTotal)} budget", color = Piggy.Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        val projection = r.projection
        val top = maxOf(r.budgetTotal, projection ?: 0, r.spends, 1L).toFloat() * 1.08f
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width; val h = size.height
            fun x(day: Float) = w * day / r.daysInMonth
            fun y(v: Long) = h - h * v / top
            // Baseline and budget pace line.
            drawLine(Piggy.Line, Offset(0f, h), Offset(w, h), 2f)
            if (r.budgetTotal > 0) {
                drawLine(Piggy.Muted, Offset(0f, h), Offset(w, y(r.budgetTotal)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                drawLine(Piggy.Line, Offset(0f, y(r.budgetTotal)), Offset(w, y(r.budgetTotal)), 2f)
            }
            if (r.cumulative.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(0f, h)
                    r.cumulative.forEachIndexed { i, v -> lineTo(x(i + 1f), y(v)) }
                }
                drawPath(path, Piggy.Accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
                if (projection != null && isCurrent) {
                    drawLine(Piggy.Accent.copy(alpha = 0.6f), Offset(x(r.daysSoFar.toFloat()), y(r.cumulative.last())),
                        Offset(w, y(projection)), 5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f)), cap = StrokeCap.Round)
                }
            }
        }
        if (projection != null && isCurrent) {
            val over = r.budgetTotal in 1 until projection
            Text(
                "At this rate: ${home.fmt(projection)} by month end" + if (r.budgetTotal > 0) (if (over) " · ${home.fmt(projection - r.budgetTotal)} over budget" else " · within budget") else "",
                color = if (over) Piggy.Accent else Piggy.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Solid: spent so far · dashed grey: budget pace · dotted: projection", color = Piggy.Muted, fontSize = 11.sp)
    }
}

/** Category bars with the pace marker (H5); riskiest first; fixed costs shown as paid (H6). */
@Composable
private fun BudgetsCard(r: MonthReport, home: Currency, onEdit: () -> Unit) {
    PiggyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Budgets", fontWeight = FontWeight.Bold, color = Piggy.Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(if (r.budgetTotal == 0L) "Set budgets" else "Edit", color = Piggy.Accent) }
        }
        if (r.budgetTotal == 0L) Text("Set a budget per category to see pace, a daily allowance and month-end projections.", color = Piggy.Muted)
        r.categories.forEach { CategoryBar(it, r.pace, home) }
    }
}

@Composable
private fun CategoryBar(l: CategoryLine, pace: Double, home: Currency) {
    val statusColor = when (l.status) {
        "Over budget", "Over pace" -> Piggy.Accent
        "Way under", "Paid · fixed" -> Piggy.Invest
        else -> Piggy.Muted
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(l.name, color = Piggy.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${home.fmt(l.spent)} / ${if (l.budget > 0) home.fmt(l.budget) else "–"}", color = Piggy.Ink)
        }
        Box(Modifier.fillMaxWidth().height(10.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawRoundRect(Piggy.Line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                val filled = ((l.ratio ?: 0.0).coerceIn(0.0, 1.0) * w).toFloat()
                drawRoundRect(if (l.status in setOf("Over budget", "Over pace")) Piggy.Accent else Piggy.Ink,
                    size = size.copy(width = filled), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                if (!l.fixed && l.budget > 0) drawLine(Piggy.Wants, Offset((pace * w).toFloat(), -4f), Offset((pace * w).toFloat(), h + 4f), 4f)
            }
        }
        Text(l.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun incomeWhen(i: IncomeLine): String {
    val m = AppController.MONTHS
    val from = "${m[i.from.month - 1]} ${i.from.year}"
    return when {
        i.schedule.name == "ONE_OFF" -> "One-off · $from"
        else -> buildString {
            append("Monthly · from $from")
            i.to?.let { append(" to ${m[it.month - 1]} ${it.year}") }
            if (i.monthsMask != 0xFFF) append(" · " + (0..11).filter { (i.monthsMask shr it) and 1 == 1 }.joinToString(", ") { m[it] })
        }
    }
}
