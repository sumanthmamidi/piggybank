package app.piggybank.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.piggybank.core.Money

/** Slider stops for "Tag everything under", in whole units of the home currency. */
private val UNDER_STEPS = listOf(50, 100, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000)

internal fun periodLabel(months: Int) = if (months == 1) "1 month" else "$months months"

/** The small filter icon above the catch-up cards; opens the catch-up filters. */
@Composable
internal fun CatchUpFilterButton(s: UiState, c: AppController) {
    if (s.historyMonths == null) return
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (s.historyMonths == 0) "Catch-up cleared" else "Last ${periodLabel(s.historyMonths)}",
            color = Piggy.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clickable { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiggyIcon(PiggyIcons.Filter, Piggy.Ink, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Filter", color = Piggy.Ink, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (open) CatchUpFilters(s, c) { open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatchUpFilters(s: UiState, c: AppController, onDismiss: () -> Unit) {
    val home = s.home
    // ---- Tag everything under ----
    var step by remember { mutableFloatStateOf(1f) }
    val under = UNDER_STEPS[step.toInt()]
    val underMinor = Money.parse(under.toString(), home)!!.minor
    val picked = s.catchUpSmall.filter { it.homeMinor < underMinor }
    val spends = s.categories.filter { it.topLevel == "SPENDS" }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var needWant by remember { mutableStateOf<String?>(null) }
    var confirmTag by remember { mutableStateOf(false) }
    // ---- Period ----
    var period by remember { mutableStateOf<Int?>(null) } // chosen, not yet applied; 0 = clear

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catch-up filters") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tag small payments", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text("Tag everything under ${home.fmt(underMinor)}", color = Piggy.Ink)
                Slider(
                    value = step, onValueChange = { step = it; confirmTag = false },
                    valueRange = 0f..(UNDER_STEPS.size - 1).toFloat(), steps = UNDER_STEPS.size - 2,
                    colors = SliderDefaults.colors(thumbColor = Piggy.Ink, activeTrackColor = Piggy.Ink),
                )
                Text(
                    if (picked.isEmpty()) "No catch-up payments under ${home.fmt(underMinor)}."
                    else "${picked.size} payment${if (picked.size == 1) "" else "s"} · ${home.fmt(picked.sumOf { it.homeMinor })} in all",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    spends.forEach { cat ->
                        Chip(cat.name, categoryId == cat.id) { categoryId = cat.id; needWant = cat.defaultNeedWant ?: needWant; confirmTag = false }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("This is a", color = Piggy.Muted)
                    Chip("Need", needWant == "NEED", Piggy.Needs) { needWant = "NEED" }
                    Chip("Want", needWant == "WANT", Piggy.Wants) { needWant = "WANT" }
                }
                val catName = spends.firstOrNull { it.id == categoryId }?.name
                if (confirmTag && catName != null) {
                    Text("Tag ${picked.size} payments as $catName? Nothing is remembered for future payments. " +
                        "Possible transfers, duplicates and payments needing a rate are left out.", color = Piggy.Ink,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Back", Modifier.weight(1f)) { confirmTag = false }
                        PrimaryButton("Tag ${picked.size}", Modifier.weight(1f)) {
                            c.tagSmallPayments(underMinor, categoryId!!, needWant); onDismiss()
                        }
                    }
                } else {
                    PrimaryButton(
                        if (catName == null) "Pick a category" else "Tag ${picked.size} as $catName",
                        Modifier.fillMaxWidth(), enabled = catName != null && needWant != null && picked.isNotEmpty(),
                    ) { confirmTag = true }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Piggy.Line)

                Text("Catch-up period", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                val current = s.historyMonths ?: 0
                Text(if (current == 0) "Cleared. Pick a period to read past SMS again." else "Covers SMS from the last ${periodLabel(current)}.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppController.PERIODS.forEach { n ->
                        Chip(periodLabel(n), (period ?: current) == n) { period = n.takeIf { it != current } }
                    }
                    if (current != 0) Chip("Clear", period == 0, Piggy.Accent) { period = 0 }
                }
                period?.let { p ->
                    val drops = s.periodDrops[p] ?: 0
                    Text(
                        when {
                            p > current -> "Reads your SMS from the last ${periodLabel(p)}. Older payments join catch-up; nothing is removed."
                            drops == 0 -> "No untagged payments to remove. Catch-up will cover the last ${periodLabel(p)}."
                            p == 0 -> "Removes all $drops untagged catch-up payments. Tagged payments stay. Pick a period later to read them back from your inbox."
                            else -> "Removes $drops untagged payments older than ${periodLabel(p)}. Tagged payments stay. A longer period later reads them back from your inbox."
                        },
                        color = if (p < current && drops > 0) Piggy.Accent else Piggy.Ink, style = MaterialTheme.typography.bodySmall,
                    )
                    PrimaryButton(
                        when { p > current -> "Read ${periodLabel(p)}"; p == 0 -> "Clear catch-up"; drops > 0 -> "Remove $drops"; else -> "Apply" },
                        Modifier.fillMaxWidth(),
                    ) { c.setCatchUpPeriod(p); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Piggy.Ink, fontWeight = FontWeight.Bold) } },
        containerColor = Piggy.Card,
    )
}
