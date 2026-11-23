package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TODAY("Today", PiggyIcons.Today), REVIEW("Review", PiggyIcons.Review),
    MONTH("Monthly", PiggyIcons.Monthly), RULES("Rules", PiggyIcons.Rules),
}

/** Review tab sections. */
object ReviewSection { const val TODAY = 0; const val CATCH_UP = 1; const val SMS = 2 }

@Composable
fun App(controller: AppController) {
    val state = controller.state ?: return
    PiggyTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
        var section by rememberSaveable { mutableIntStateOf(ReviewSection.TODAY) }
        val openReview = { s: Int -> section = s; tab = Tab.REVIEW }
        val openMonth = { tab = Tab.MONTH }
        Scaffold(
            containerColor = Piggy.Ground,
            bottomBar = {
                NavigationBar(containerColor = Piggy.Card) {
                    Tab.entries.forEach { t ->
                        val selected = t == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = t },
                            icon = {
                                // A dot on Review while today's payments wait.
                                val badge = t == Tab.REVIEW && state.queueCount > 0 && !state.dayClosed
                                Box {
                                    PiggyIcon(t.icon, if (selected) Piggy.Ink else Piggy.Muted, 24.dp)
                                    if (badge) Box(
                                        Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(9.dp)
                                            .background(Piggy.Accent, CircleShape),
                                    )
                                }
                            },
                            label = { Text(t.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Piggy.Ink,
                                unselectedTextColor = Piggy.Muted,
                                indicatorColor = Piggy.Ground,
                            ),
                        )
                    }
                }
            },
        ) { inner ->
            // consumeWindowInsets: the keyboard's padding then excludes the bottom bar already padded for.
            val m = Modifier.padding(inner).consumeWindowInsets(inner)
            when (tab) {
                Tab.TODAY -> TodayScreen(state, controller, openReview, openMonth, m)
                Tab.REVIEW -> ReviewScreen(state, controller, section, { section = it }, m)
                Tab.MONTH -> MonthScreen(state, controller, m)
                Tab.RULES -> RulesScreen(state, controller, m)
            }
            RestoreDialogs(state, controller)
        }
    }
}
