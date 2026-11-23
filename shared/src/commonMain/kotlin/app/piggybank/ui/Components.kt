package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
fun Title(text: String, sub: String? = null) {
    Column {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Piggy.Ink)
        sub?.let { Text(it, color = Piggy.Muted) }
    }
}

@Composable
fun Section(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.Bold, color = Piggy.Ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = Piggy.Accent) }
    }
}

@Composable
fun PiggyCard(modifier: Modifier = Modifier, dark: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(if (dark) Piggy.Ink else Piggy.Card, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Piggy.Ink, contentColor = Color.White),
    ) { Text(text, maxLines = 1, fontWeight = FontWeight.Bold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, color = Piggy.Ink, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, color: Color = Piggy.Ink, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) color else Piggy.Card, RoundedCornerShape(50))
            .border(1.dp, if (selected) color else Piggy.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) Color.White else Piggy.Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Piggy.Line, RoundedCornerShape(14.dp)).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f)
                    .background(if (i == selected) Piggy.Card else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, maxLines = 1, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (i == selected) Piggy.Ink else Piggy.Muted)
            }
        }
    }
}

fun kindColor(kind: String, needWant: String?): Color = when {
    kind == "INVESTMENT" -> Piggy.Invest
    kind in AppController.HIDDEN -> Piggy.Hidden
    needWant == "NEED" -> Piggy.Needs
    needWant == "WANT" -> Piggy.Wants
    else -> Piggy.Line
}

@Composable
fun TxnRow(t: TxnItem, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(Piggy.Card, RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).padding(vertical = 14.dp).background(kindColor(t.kind, t.needWant), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, fontWeight = FontWeight.Bold, color = Piggy.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (t.place != null) { Spacer(Modifier.width(4.dp)); PiggyIcon(PiggyIcons.Pin, Piggy.Accent, 14.dp) }
            }
            val tag = when {
                t.kind != "SPEND" -> AppController.kindLabel(t.kind) + (t.category?.takeIf { t.kind == "INVESTMENT" }?.let { " · $it" } ?: "")
                t.category != null -> t.category + (t.needWant?.let { " · ${AppController.nwLabel(it)}" } ?: "")
                else -> "Not tagged yet"
            }
            Text(
                listOf(tag, t.whenLabel, t.subtitle).filter { it.isNotEmpty() }.joinToString(" · ") + (if (t.auto) " · auto" else ""),
                color = Piggy.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
            t.refunded?.let { Text("Refunded $it", color = Piggy.Invest, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(t.amount, fontWeight = FontWeight.Bold, color = Piggy.Ink)
            trailing?.invoke()
        }
    }
}

/** A card title with a small leading icon. */
@Composable
fun IconTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = Piggy.Ink, tint: Color = color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PiggyIcon(icon, tint, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, color = color)
    }
}
