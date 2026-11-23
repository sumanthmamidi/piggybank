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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RulesScreen(s: UiState, c: AppController, modifier: Modifier, header: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header()
        Text("Learned from your reviews. Each one tags a payee automatically.", color = Piggy.Muted)

        PiggyCard(dark = true) {
            Text("Auto-tagged", color = Color(0xFFB5B9C3))
            Text(s.autoRate?.let { "$it%" } ?: "–", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("of reviewed payments were tagged without you. This grows as you confirm with \"Always tag\" on.",
                color = Color(0xFFB5B9C3), style = MaterialTheme.typography.bodySmall)
        }

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
                    PiggySwitch(r.alwaysAsk, { on -> c.setAlwaysAsk(r.id, on) })
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
internal fun SavePlacesCard(s: UiState, c: AppController) {
    // Google Play's prominent disclosure: explain background location before Android's permission prompt.
    var disclose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val withDisclosure = { then: () -> Unit -> if (s.placeAccess == PlaceAccess.ALWAYS) then() else disclose = then }
    PiggyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                IconTitle(PiggyIcons.Pin, "Save where I paid")
                Text("When a payment SMS arrives, note where your phone is. Skips autopay, mandates, bills and foreign charges. " +
                    "Stays on this phone; Open in Maps hands the spot to your maps app.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            PiggySwitch(s.savePlaces, { on -> if (on) withDisclosure { c.setSavePlaces(true) } else c.setSavePlaces(false) })
        }
        if (s.savePlaces) when (s.placeAccess) {
            PlaceAccess.ALWAYS -> Text("On for new payments, even with the app closed.", color = Piggy.Invest, style = MaterialTheme.typography.bodySmall)
            PlaceAccess.WHILE_OPEN -> {
                Text("Only works while PiggyBank is open. Choose \"Allow all the time\" so it works when the SMS arrives with the app closed.",
                    color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { withDisclosure(c::requestPlaceAccess) }) { Text("Allow all the time", color = Piggy.Accent) }
            }
            PlaceAccess.NONE -> {
                Text("Location access is off, so nothing is saved.", color = Piggy.Accent, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { withDisclosure(c::requestPlaceAccess) }) { Text("Allow location", color = Piggy.Accent) }
            }
        }
    }
    disclose?.let { then -> LocationDisclosure(onDismiss = { disclose = null }, onContinue = { disclose = null; then() }) }
}

@Composable
internal fun LocationDisclosure(onDismiss: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { PiggyIcon(PiggyIcons.Pin, Piggy.Ink, 28.dp) },
        title = { Text("Use your location?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PiggyBank collects location data to save where you paid, even when the app is closed or not in use.",
                    color = Piggy.Ink, fontWeight = FontWeight.Bold)
                Text("When a payment SMS arrives, PiggyBank notes your phone's location once and keeps it with that payment, " +
                    "so you can see it on a map later. It's stored only on this phone and is never sent to the developer. " +
                    "It's included in your backups, including Google Drive if you turn that on.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Android asks next. Choose \"Allow all the time\" for it to work with the app closed. " +
                    "You can turn this off here at any time.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue", color = Piggy.Ink, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = Piggy.Muted) } },
    )
}

/** M4: local backups to Documents/PiggyBank, nightly and on demand, and restore. */
@Composable
internal fun BackupCard(s: UiState, c: AppController) {
    val b = s.backup
    PiggyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                IconTitle(PiggyIcons.Backup, "Backup")
                Text("Every night around 2 AM to ${b.folder}. That folder stays when the app is uninstalled. " +
                    "Keeps 7 daily, 4 weekly and 3 monthly backups.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            }
            PiggySwitch(b.nightly, { c.setNightlyBackup(it) })
        }
        Text(b.last?.let { "Last backup: $it" } ?: "No backup yet.", color = Piggy.Ink, style = MaterialTheme.typography.bodySmall)
        b.lastError?.let { Text("Last try failed: $it", color = Piggy.Accent, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (s.backingUp) "Backing up…" else "Back up now", Modifier.weight(1f), enabled = !s.backingUp) { c.backUpNow() }
            SecondaryButton("Restore…", Modifier.weight(1f)) { c.restore(fromFile = false) }
        }
        Row {
            TextButton(onClick = { c.restore(fromFile = true) }) { Text("From a file…", color = Piggy.Accent) }
            TextButton(onClick = c::restoreFromDrive) { Text("From Google Drive…", color = Piggy.Accent) }
        }

        HorizontalDivider(color = Piggy.Line)
        Text("Google Drive (optional)", fontWeight = FontWeight.Bold, color = Piggy.Ink)
        if (b.driveAccount == null) {
            Text("Also keep each night's backup in your Google Drive, in a hidden folder only PiggyBank can open. " +
                "It's the only thing PiggyBank ever sends online.", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
            SecondaryButton("Connect Google Drive", Modifier.fillMaxWidth()) { c.connectDrive() }
        } else {
            Text(b.driveAccount + " · " + (b.driveLast?.let { "last upload $it" } ?: "no upload yet"), color = Piggy.Ink, style = MaterialTheme.typography.bodySmall)
            b.drivePending?.let { Text("Drive backup pending: $it", color = Piggy.Accent, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = c::connectDrive) { Text("Reconnect", color = Piggy.Accent) }
                TextButton(onClick = c::disconnectDrive) { Text("Turn off Drive", color = Piggy.Muted) }
            }
        }
    }
}
