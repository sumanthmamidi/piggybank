package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SettingsPage(val title: String) { HOME("Settings"), RULES("Rules"), BACKUP("Backup & restore"), PLACES("Save where I paid") }

/** Settings: a list of entries, each opening its own page. Back goes up one level. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(page: SettingsPage, s: UiState, c: AppController, onPage: (SettingsPage?) -> Unit, modifier: Modifier) {
    val up = { onPage(if (page == SettingsPage.HOME) null else SettingsPage.HOME) }
    BackHandler(onBack = up)
    val header = @Composable { PageHeader(page.title, up) }
    if (page == SettingsPage.RULES) return RulesScreen(s, c, modifier, header)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header()
        when (page) {
            SettingsPage.HOME -> {
                val b = s.backup
                SettingsRow(PiggyIcons.Rules, "Rules", "${s.rules.size} rules · ${s.autoRate?.let { "$it% auto-tagged" } ?: "accounts, formats, duplicates"}") { onPage(SettingsPage.RULES) }
                SettingsRow(PiggyIcons.Backup, "Backup & restore",
                    (b.last?.let { "Last backup $it" } ?: "No backup yet") + if (b.driveAccount != null) " · Google Drive on" else "") { onPage(SettingsPage.BACKUP) }
                SettingsRow(PiggyIcons.Pin, "Save where I paid", if (s.savePlaces) "On" else "Off") { onPage(SettingsPage.PLACES) }
            }
            SettingsPage.BACKUP -> BackupCard(s, c)
            SettingsPage.PLACES -> SavePlacesCard(s, c)
            SettingsPage.RULES -> Unit
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Piggy.Card, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PiggyIcon(icon, Piggy.Ink, 24.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Piggy.Ink)
            Text(sub, color = Piggy.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PiggyIcon(PiggyIcons.ChevronRight, Piggy.Muted, 20.dp)
    }
}
