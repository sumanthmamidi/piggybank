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
import androidx.compose.runtime.remember
import app.piggybank.payments.PaymentsFilter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TODAY("Today", PiggyIcons.Today), REVIEW("Review", PiggyIcons.Review),
    PAYMENTS("Payments", PiggyIcons.Payments), MONTH("Monthly", PiggyIcons.Monthly),
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
        var settings by remember { mutableStateOf<SettingsPage?>(null) }
        // Monthly → a category: Payments opens filtered; back returns to Monthly.
        var paymentsFromMonth by remember { mutableStateOf(false) }
        val openPayments = { f: PaymentsFilter, fromMonth: Boolean ->
            controller.setPaymentsFilter(f); paymentsFromMonth = fromMonth; settings = null; tab = Tab.PAYMENTS
        }
        Scaffold(
            containerColor = Piggy.Ground,
            bottomBar = {
                NavigationBar(containerColor = Piggy.Card) {
                    Tab.entries.forEach { t ->
                        val selected = t == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { settings = null; if (t == Tab.PAYMENTS && tab != Tab.PAYMENTS) paymentsFromMonth = false; tab = t },
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
            CompositionLocalProvider(LocalOpenSettings provides { settings = SettingsPage.HOME }) {
                val page = settings
                if (page != null) SettingsScreen(page, state, controller, { settings = it }, m)
                else when (tab) {
                    Tab.TODAY -> TodayScreen(state, controller, openReview, openMonth, m)
                    Tab.REVIEW -> ReviewScreen(state, controller, section, { section = it }, m)
                    Tab.PAYMENTS -> PaymentsScreen(state, controller, m,
                        onBack = if (paymentsFromMonth) ({ paymentsFromMonth = false; tab = Tab.MONTH }) else null)
                    Tab.MONTH -> MonthScreen(state, controller, m) { f -> openPayments(f, true) }
                }
            }
            RestoreDialogs(state, controller)
        }
    }
}
