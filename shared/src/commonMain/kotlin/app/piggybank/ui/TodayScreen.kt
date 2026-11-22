package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TodayScreen(s: UiState, c: AppController, openReview: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            Title("Today", s.todayLabel)
        }
        if (s.access != SmsAccess.READY) SmsSetupCard(s, c)

        // G1: spends today, split by Need/Want; investments shown apart, never inside "spent".
        PiggyCard(dark = true) {
            Text("Spent today", color = Color(0xFFB5B9C3))
            Text(s.spentToday, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Needs", s.needs, Piggy.Needs, Modifier.weight(1f))
                Tile("Wants", s.wants, Piggy.Wants, Modifier.weight(1f))
                Tile("Untagged", s.untagged, Piggy.Hidden, Modifier.weight(1f))
            }
            Text("Invested today ${s.invested} · not counted as spending", color = Color(0xFFB5B9C3), style = MaterialTheme.typography.bodySmall)
        }

        // F2/F3: the daily close.
        if (s.dayClosed) {
            PiggyCard {
                Text("Day closed ✓", fontWeight = FontWeight.Bold, color = Piggy.Invest)
                Text("${s.streak}-day streak. New payments will still show up here.", color = Piggy.Muted)
            }
        } else {
            PiggyCard {
                Text(
                    if (s.queueCount == 0) "Nothing to review today" else "${s.queueCount} payment${if (s.queueCount == 1) "" else "s"} to review",
                    fontWeight = FontWeight.Bold, color = Piggy.Ink,
                )
                Text(if (s.streak > 0) "${s.streak}-day streak · close today to keep it" else "Close your day to start a streak", color = Piggy.Muted)
                PrimaryButton("Close your day", Modifier.fillMaxWidth()) { openReview(ReviewSection.TODAY) }
            }
        }

        if (s.catchUpCount > 0) {
            LinkCard(
                "Catch-up: ${s.catchUpCount} past payments",
                "From the SMS history you imported. Tag them any time; they're grouped by payee.",
            ) { openReview(ReviewSection.CATCH_UP) }
        }
        if (s.candidates.isNotEmpty()) {
            LinkCard("SMS not read as a payment? Pick it", "${s.candidates.size} SMS mention an amount but weren't read as a payment.") {
                openReview(ReviewSection.SMS)
            }
        }

        // G2: hidden items fold into one line; "Count it" brings one back (D9).
        if (s.notCounted.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            Text(
                "${s.notCounted.size} hidden today (self-transfers, card bills) ${if (open) "▲" else "▼"}",
                color = Piggy.Muted, modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
            )
            if (open) s.notCounted.forEach { t ->
                TxnRow(t, trailing = { TextButton(onClick = { c.countIt(t.id) }) { Text("Count it", color = Piggy.Accent) } })
            }
        }

        Section("Today's payments")
        if (s.todayItems.isEmpty()) Text("No payments yet today.", color = Piggy.Muted)
        s.todayItems.forEach { TxnRow(it) }

        if (s.access == SmsAccess.READY) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (s.syncing) "Checking for new SMS…" else s.lastResult ?: "New SMS are read as they arrive.",
                    color = Piggy.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                if (!s.syncing) TextButton(onClick = c::syncNow) { Text("Check now", color = Piggy.Accent) }
            }
        }
        Text("No network permission · all data stays on this phone", color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Color(0xFF23262E), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row {
            Text("● ", color = color, fontSize = 12.sp)
            Text(label, color = Color(0xFFB5B9C3), fontSize = 12.sp)
        }
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LinkCard(title: String, body: String, onClick: () -> Unit) {
    PiggyCard(Modifier.clickable(onClick = onClick)) {
        Text("$title ›", fontWeight = FontWeight.Bold, color = Piggy.Ink)
        Text(body, color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SmsSetupCard(s: UiState, c: AppController) {
    PiggyCard {
        when (s.access) {
            SmsAccess.NOT_ASKED, SmsAccess.DENIED -> {
                Text("Read your bank SMS", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text(
                    "PiggyBank reads SMS that mention an amount, from any bank, card or wallet, to build your spends. " +
                        "Messages are read on this phone only; the app has no internet access.",
                    color = Piggy.Muted,
                )
                if (s.access == SmsAccess.DENIED) {
                    Text(
                        "SMS access is off. You can still restore a backup and add entries by hand. " +
                            "To turn it on: Settings › Apps › PiggyBank › Permissions › SMS.",
                        color = Piggy.Accent,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Allow SMS access") { c.requestSmsAccess() }
                    if (s.access == SmsAccess.DENIED) SecondaryButton("Open settings") { c.openAppSettings() }
                }
            }
            SmsAccess.GRANTED_NO_IMPORT -> {
                Text("Import past SMS", fontWeight = FontWeight.Bold, color = Piggy.Ink)
                Text("How far back should PiggyBank read? Past days won't need a daily review.", color = Piggy.Muted)
                if (s.syncing) Text("Reading your inbox…", color = Piggy.Ink)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12).forEach { n -> PrimaryButton("$n months", Modifier.weight(1f)) { c.importHistory(n) } }
                }
            }
            SmsAccess.READY -> Unit
        }
    }
}
