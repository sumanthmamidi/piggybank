package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RulesScreen(s: UiState, c: AppController, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Rules", "Learned from your reviews. Each one tags a payee automatically.")

        PiggyCard(dark = true) {
            Text("Auto-tagged", color = Color(0xFFB5B9C3))
            Text(s.autoRate?.let { "$it%" } ?: "–", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("of reviewed payments were tagged without you. This grows as you confirm with \"Always tag\" on.",
                color = Color(0xFFB5B9C3), style = MaterialTheme.typography.bodySmall)
        }

        SavePlacesCard(s, c)

        Section("Your rules (${s.rules.size})")
        if (s.rules.isEmpty()) Text("No rules yet. Confirm a payment with \"Always tag like this\" on.", color = Piggy.Muted)
        s.rules.forEach { r ->
            Column(Modifier.fillMaxWidth().background(Piggy.Card, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.match, fontWeight = FontWeight.Bold, color = Piggy.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ ${r.result} · used ${r.hits}×", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { c.deleteRule(r.id) }) { Text("Delete", color = Piggy.Accent) }
                }
                // E5
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Always ask me", color = Piggy.Ink, modifier = Modifier.weight(1f))
                    Switch(r.alwaysAsk, { on -> c.setAlwaysAsk(r.id, on) }, colors = SwitchDefaults.colors(checkedTrackColor = Piggy.Ink))
                }
            }
        }

        Section("Your accounts and cards")
        Text("Learned from your SMS. Money moving between these is hidden as a self-transfer or card bill.",
            color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
        PiggyCard {
            if (s.ownAccounts.isEmpty()) Text("None yet.", color = Piggy.Muted)
            s.ownAccounts.forEach { Text(it, color = Piggy.Ink) }
        }

        if (s.duplicates.isNotEmpty()) {
            Section("Duplicates (${s.duplicates.size})")
            Text("Left out of every total. Undo puts one back.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            s.duplicates.forEach { t ->
                TxnRow(t, trailing = { TextButton(onClick = { c.undoDuplicate(t.id) }) { Text("Undo", color = Piggy.Accent) } })
            }
        }

        Section("SMS formats")
        PiggyCard { Text(s.formats, color = Piggy.Ink) }
    }
}

/** Opt-in: attach the phone's location to payment SMS as they arrive. */
@Composable
private fun SavePlacesCard(s: UiState, c: AppController) {
    PiggyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save where I paid", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text("When a payment SMS arrives, note where your phone is. Skips autopay, mandates, bills and foreign charges. " +
                    "Stays on this phone; Open in Maps hands the spot to your maps app.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.savePlaces, { c.setSavePlaces(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Piggy.Ink))
        }
        if (s.savePlaces) when (s.placeAccess) {
            PlaceAccess.ALWAYS -> Text("On for new payments, even with the app closed.", color = Piggy.Invest, style = MaterialTheme.typography.bodySmall)
            PlaceAccess.WHILE_OPEN -> {
                Text("Only works while PiggyBank is open. Choose \"Allow all the time\" so it works when the SMS arrives with the app closed.",
                    color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = c::requestPlaceAccess) { Text("Allow all the time", color = Piggy.Accent) }
            }
            PlaceAccess.NONE -> {
                Text("Location access is off, so nothing is saved.", color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = c::requestPlaceAccess) { Text("Allow location", color = Piggy.Accent) }
            }
        }
    }
}
