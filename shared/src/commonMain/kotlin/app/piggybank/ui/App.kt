package app.piggybank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

enum class Tab(val label: String) { TODAY("Today"), REVIEW("Review"), MONTH("Monthly"), RULES("Rules") }

/** Review tab sections. */
object ReviewSection { const val TODAY = 0; const val CATCH_UP = 1; const val SMS = 2 }

@Composable
fun App(controller: AppController) {
    val state = controller.state ?: return
    PiggyTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
        var section by rememberSaveable { mutableIntStateOf(ReviewSection.TODAY) }
        val openReview = { s: Int -> section = s; tab = Tab.REVIEW }
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
                                val badge = t == Tab.REVIEW && state.queueCount > 0 && !state.dayClosed
                                Box(
                                    Modifier.size(if (badge) 12.dp else 10.dp).background(
                                        if (selected || badge) Piggy.Accent else Piggy.Line, RoundedCornerShape(3.dp),
                                    )
                                )
                            },
                            label = { Text(t.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Piggy.Ink,
                                unselectedTextColor = Piggy.Muted,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            },
        ) { inner ->
            val m = Modifier.padding(inner)
            when (tab) {
                Tab.TODAY -> TodayScreen(state, controller, openReview, m)
                Tab.REVIEW -> ReviewScreen(state, controller, section, { section = it }, m)
                Tab.MONTH -> Column(m.fillMaxSize().padding(20.dp)) {
                    Title("Monthly review", "Budgets, income, savings and charts arrive in M3.")
                }
                Tab.RULES -> RulesScreen(state, controller, m)
            }
        }
    }
}
