package app.piggybank.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PiggyBank's own line icons (24×24, 2px rounded strokes), drawn here so the app needs no icon
 * library. They're tinted by [PiggyIcon], so one set works for every colour.
 */
object PiggyIcons {
    private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        ).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, false, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, false, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t); lineTo(r - rad, t); arcToRelative(rad, rad, 0f, false, true, rad, rad)
        lineTo(r, b - rad); arcToRelative(rad, rad, 0f, false, true, -rad, rad)
        lineTo(l + rad, b); arcToRelative(rad, rad, 0f, false, true, -rad, -rad)
        lineTo(l, t + rad); arcToRelative(rad, rad, 0f, false, true, rad, -rad); close()
    }

    /** Today: a calendar page. */
    val Today = line("today") {
        roundRect(4f, 5f, 20f, 20f, 2.5f)
        moveTo(4f, 10f); lineTo(20f, 10f)
        moveTo(8f, 3f); lineTo(8f, 7f)
        moveTo(16f, 3f); lineTo(16f, 7f)
        moveTo(11.5f, 14.5f); lineTo(12.5f, 14.5f)
    }

    /** Review: a card with a tick. */
    val Review = line("review") {
        roundRect(4f, 4f, 20f, 20f, 3f)
        moveTo(8f, 12.5f); lineTo(11f, 15.5f); lineTo(16.5f, 9f)
    }

    /** Monthly: bars over a baseline. */
    val Monthly = line("monthly") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7f, 16.5f); lineTo(7f, 12f)
        moveTo(12f, 16.5f); lineTo(12f, 6f)
        moveTo(17f, 16.5f); lineTo(17f, 9.5f)
    }

    /** Rules: two sliders. */
    val Rules = line("rules") {
        moveTo(4f, 7f); lineTo(6.5f, 7f); moveTo(11.5f, 7f); lineTo(20f, 7f)
        circle(9f, 7f, 2.5f)
        moveTo(4f, 17f); lineTo(12.5f, 17f); moveTo(17.5f, 17f); lineTo(20f, 17f)
        circle(15f, 17f, 2.5f)
    }

    /** Filter: three lines getting shorter. */
    val Filter = line("filter") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(7f, 12f); lineTo(17f, 12f)
        moveTo(10f, 18f); lineTo(14f, 18f)
    }

    /** A map pin. */
    val Pin = line("pin") {
        moveTo(12f, 21f)
        curveTo(12f, 21f, 5f, 14.5f, 5f, 9.5f)
        arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
        curveTo(19f, 14.5f, 12f, 21f, 12f, 21f); close()
        circle(12f, 9.5f, 2.5f)
    }

    /** Backup: a cloud with an up arrow. */
    val Backup = line("backup") {
        moveTo(7f, 18f)
        arcToRelative(4f, 4f, 0f, false, true, -0.5f, -7.97f)
        arcToRelative(6f, 6f, 0f, false, true, 11.6f, -1.03f)
        arcToRelative(4.5f, 4.5f, 0f, false, true, -0.6f, 9f)
        moveTo(12f, 20f); lineTo(12f, 12.5f)
        moveTo(9.5f, 15f); lineTo(12f, 12.5f); lineTo(14.5f, 15f)
    }

    /** Check now: a circular arrow. */
    val Refresh = line("refresh") {
        moveTo(19.5f, 12f)
        arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f)
        moveTo(19.5f, 4.5f); lineTo(19.5f, 8.5f); lineTo(15.5f, 8.5f)
    }

    val Plus = line("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** Catch-up: a clock with an arrow going back. */
    val History = line("history") {
        moveTo(4.5f, 12f)
        arcToRelative(7.5f, 7.5f, 0f, true, false, 2.2f, -5.3f)
        moveTo(4.5f, 4.5f); lineTo(4.5f, 8.5f); lineTo(8.5f, 8.5f)
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(14.5f, 14f)
    }

    /** SMS: a speech bubble. */
    val Message = line("message") {
        moveTo(5f, 5f); lineTo(19f, 5f)
        arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
        lineTo(20.5f, 15f)
        arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
        lineTo(10f, 16.5f); lineTo(6f, 20f); lineTo(6f, 16.5f); lineTo(5f, 16.5f)
        arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
        lineTo(3.5f, 6.5f)
        arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close()
        moveTo(8f, 9.5f); lineTo(16f, 9.5f)
        moveTo(8f, 12.5f); lineTo(13f, 12.5f)
    }

    /** Done: a tick in a circle. */
    val Done = line("done") {
        circle(12f, 12f, 8.5f)
        moveTo(8.5f, 12.5f); lineTo(11f, 15f); lineTo(15.5f, 9.5f)
    }

    /** Streak: a flame. */
    val Flame = line("flame") {
        moveTo(12f, 21f)
        curveTo(8f, 21f, 6f, 18f, 6f, 15f)
        curveTo(6f, 11f, 10f, 9f, 10f, 4f)
        curveTo(13f, 6f, 14f, 9f, 13.5f, 11f)
        curveTo(15f, 10.5f, 15.5f, 9f, 15.5f, 8f)
        curveTo(17.5f, 10f, 18f, 12.5f, 18f, 15f)
        curveTo(18f, 18f, 16f, 21f, 12f, 21f); close()
    }

    val Trash = line("trash") {
        moveTo(4.5f, 7f); lineTo(19.5f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 7f)
    }

    val ChevronRight = line("chevron") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }

    /** Restore: a tray with an arrow coming down into it. */
    val Restore = line("restore") {
        moveTo(12f, 4f); lineTo(12f, 14f)
        moveTo(8.5f, 10.5f); lineTo(12f, 14f); lineTo(15.5f, 10.5f)
        moveTo(4.5f, 14.5f); lineTo(4.5f, 18.5f); lineTo(19.5f, 18.5f); lineTo(19.5f, 14.5f)
    }

    /** Payment to a person (no category guess). */
    val Warning = line("warning") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 13.5f)
        moveTo(12f, 16.5f); lineTo(12f, 16.6f)
    }
}

@Composable
fun PiggyIcon(icon: ImageVector, tint: Color = Piggy.Ink, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
