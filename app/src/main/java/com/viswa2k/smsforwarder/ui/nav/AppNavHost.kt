package com.viswa2k.smsforwarder.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.home.HomeScreen
import com.viswa2k.smsforwarder.ui.rules.RuleEditorScreen
import com.viswa2k.smsforwarder.ui.rules.RulesScreen

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.RULES, R.string.nav_rules, Icons.Outlined.Rule),
    Tab(Routes.LOG, R.string.nav_log, Icons.Outlined.History),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // Task 15 wires the PIN prompt here; until then guarded actions simply run.
    val requireUnlock: (() -> Unit) -> Unit = { action -> action() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (current in Routes.TABS) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { if (current != tab.route) navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    requireUnlock = requireUnlock,
                    onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                    onOpenRules = { navController.navigateToTab(Routes.RULES) },
                    onOpenLog = { navController.navigateToTab(Routes.LOG) },
                    onOpenRule = { id -> navController.navigate(Routes.ruleEdit(id)) },
                )
            }
            composable(Routes.RULES) {
                RulesScreen(
                    requireUnlock = requireUnlock,
                    onOpenRule = { id -> navController.navigate(Routes.ruleEdit(id)) },
                    onNewRule = { navController.navigate(Routes.ruleEdit(0)) },
                )
            }
            composable(
                Routes.RULE_EDIT,
                arguments = listOf(navArgument(Routes.RULE_ID_ARG) { type = NavType.LongType }),
            ) { entry ->
                RuleEditorScreen(
                    ruleId = entry.arguments?.getLong(Routes.RULE_ID_ARG) ?: 0L,
                    requireUnlock = requireUnlock,
                    onClose = { navController.popBackStack() },
                    onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.LOG) { StubScreen(stringResource(R.string.title_log)) }
            composable(Routes.SETTINGS) { StubScreen(stringResource(R.string.title_settings)) }
        }
    }
}
