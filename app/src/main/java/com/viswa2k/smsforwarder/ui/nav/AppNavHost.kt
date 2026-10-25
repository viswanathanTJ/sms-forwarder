package com.viswa2k.smsforwarder.ui.nav

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.onboarding.OnboardingPager
import com.viswa2k.smsforwarder.ui.onboarding.needsOnboarding
import com.viswa2k.smsforwarder.ui.settings.AppLock
import com.viswa2k.smsforwarder.ui.settings.LockedPane
import com.viswa2k.smsforwarder.ui.settings.PinEntryDialog
import com.viswa2k.smsforwarder.ui.settings.SettingsRoot
import com.viswa2k.smsforwarder.ui.settings.SettingsSection
import com.viswa2k.smsforwarder.ui.settings.SettingsSectionPage
import com.viswa2k.smsforwarder.ui.settings.settingsViewModel
import com.viswa2k.smsforwarder.ui.home.HomeScreen
import com.viswa2k.smsforwarder.ui.log.LogScreen
import com.viswa2k.smsforwarder.ui.rules.RuleEditorScreen
import com.viswa2k.smsforwarder.ui.rules.RulesScreen

private sealed interface PinState {
    data object Loading : PinState
    data class Loaded(val record: String?) : PinState
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.RULES, R.string.nav_rules, Icons.AutoMirrored.Outlined.Rule),
    Tab(Routes.LOG, R.string.nav_log, Icons.Outlined.History),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        // HOME is the root once onboarding (when shown) has been popped.
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val graph = remember { AppGraph.from(context) }
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    // First run, or a required permission missing: setup checklist before Home (decided once, off Main).
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (start == null) start = if (needsOnboarding(graph)) Routes.ONBOARDING else Routes.HOME
    }

    // PIN (spec 5.8): guards the master switch, rule edits and Settings until the app goes to the background.
    val pinRecord by remember { graph.settings.pinHash.map { PinState.Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = PinState.Loading)
    val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) AppLock.lock() }
        val process = ProcessLifecycleOwner.get().lifecycle
        process.addObserver(observer)
        onDispose { process.removeObserver(observer) }
    }
    val requireUnlock: (() -> Unit) -> Unit = { action ->
        when (val pin = pinRecord) {
            PinState.Loading -> Unit // settings not read yet: ignore the tap rather than bypass the PIN
            is PinState.Loaded -> if (pin.record == null || AppLock.unlocked.value) action() else pendingAction = action
        }
    }
    pendingAction?.let { action ->
        PinEntryDialog(
            title = stringResource(R.string.pin_enter_title),
            onSuccess = {
                AppLock.unlocked.value = true
                pendingAction = null
                action()
            },
            onDismiss = { pendingAction = null },
        )
    }

    // Settings and every settings/{section} page show the lock pane while a PIN is set and the app is locked.
    val settingsLocked = when (val pin = pinRecord) {
        PinState.Loading -> true
        is PinState.Loaded -> pin.record != null && !unlocked
    }

    val startRoute = start ?: return
    // Once the user is past onboarding (never on top of it).
    if (current != null && current != Routes.ONBOARDING) PrivacyNotice()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (current in Routes.TABS || current == Routes.SETTINGS_SECTION) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        val onSettingsPage = tab.route == Routes.SETTINGS && current == Routes.SETTINGS_SECTION
                        NavigationBarItem(
                            selected = current == tab.route || onSettingsPage,
                            onClick = {
                                when {
                                    // Settings tab on a Settings sub-page: back to the Settings root.
                                    onSettingsPage -> navController.popBackStack(Routes.SETTINGS, inclusive = false)
                                    current != tab.route -> navController.navigateToTab(tab.route)
                                }
                            },
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
        // "Remove animations" in system settings: no transitions at all.
        val reduceMotion = remember {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
        }
        val enter: (String?, String?) -> EnterTransition = { from, to ->
            if (reduceMotion) EnterTransition.None else tabEnter(from, to)
        }
        val popEnter: (String?, String?) -> EnterTransition = { from, to ->
            if (reduceMotion) EnterTransition.None else popTabEnter(from, to)
        }
        val exit: (String?, String?) -> ExitTransition = { from, to ->
            if (reduceMotion) ExitTransition.None else tabExit(from, to)
        }
        val pushIn: () -> EnterTransition = { if (reduceMotion) EnterTransition.None else pushEnter() }
        val pushOut: () -> ExitTransition = { if (reduceMotion) ExitTransition.None else pushPopExit() }
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        NavHost(
            navController,
            startDestination = startRoute,
            modifier = Modifier.padding(padding),
            enterTransition = { enter(initialState.destination.route, targetState.destination.route) },
            exitTransition = { exit(initialState.destination.route, targetState.destination.route) },
            popEnterTransition = { popEnter(initialState.destination.route, targetState.destination.route) },
            popExitTransition = { exit(initialState.destination.route, targetState.destination.route) },
        ) {
            composable(
                Routes.ONBOARDING,
                exitTransition = { if (reduceMotion) ExitTransition.None else fadeAway() },
            ) {
                OnboardingPager(onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                })
            }
            composable(
                Routes.HOME,
                enterTransition = {
                    if (initialState.destination.route == Routes.ONBOARDING) {
                        if (reduceMotion) EnterTransition.None else fade()
                    } else enter(initialState.destination.route, targetState.destination.route)
                },
            ) {
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
                enterTransition = { pushIn() },
                exitTransition = { pushOut() },
                popEnterTransition = { pushIn() },
                popExitTransition = { pushOut() },
            ) { entry ->
                RuleEditorScreen(
                    ruleId = entry.arguments?.getLong(Routes.RULE_ID_ARG) ?: 0L,
                    requireUnlock = requireUnlock,
                    onClose = { navController.popBackStack() },
                    onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.LOG) { LogScreen() }
            composable(Routes.SETTINGS) { entry ->
                if (settingsLocked) {
                    LockedPane(onUnlock = { requireUnlock { } })
                } else {
                    SettingsRoot(
                        vm = settingsViewModel(entry),
                        onOpen = { section ->
                            // Only from the settled root: a quick second tap does not stack two pages.
                            if (navController.currentBackStackEntry == entry) navController.navigate(Routes.settingsSection(section.key))
                        },
                    )
                }
            }
            composable(
                Routes.SETTINGS_SECTION,
                arguments = listOf(navArgument(Routes.SECTION_ARG) { type = NavType.StringType }),
                enterTransition = { pushIn() },
                exitTransition = { pushOut() },
                popEnterTransition = { pushIn() },
                popExitTransition = { pushOut() },
            ) { entry ->
                val section = SettingsSection.fromKey(entry.arguments?.getString(Routes.SECTION_ARG))
                // Shares the root's ViewModel; falls back to this entry if the root is not on the stack.
                val owner = remember(entry) { runCatching { navController.getBackStackEntry(Routes.SETTINGS) }.getOrDefault(entry) }
                when {
                    // A child of the Settings gate: deep navigation never bypasses the PIN.
                    settingsLocked -> LockedPane(onUnlock = { requireUnlock { } })
                    section == null -> LaunchedEffect(entry) { navController.popBackStack() }
                    else -> SettingsSectionPage(section, settingsViewModel(owner), onBack = {
                        // Ignore a second tap while the page is already closing.
                        if (navController.currentBackStackEntry == entry) navController.popBackStack()
                    })
                }
            }
        }
        }
    }
}
