package com.viswa2k.smsforwarder.ui.onboarding

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.OemSettings
import com.viswa2k.smsforwarder.ui.common.ToneChip
import com.viswa2k.smsforwarder.ui.common.neutralTone
import com.viswa2k.smsforwarder.ui.nav.LocalReduceMotion
import com.viswa2k.smsforwarder.ui.nav.Transitions
import com.viswa2k.smsforwarder.ui.settings.AppLock
import com.viswa2k.smsforwarder.ui.settings.OemItem
import com.viswa2k.smsforwarder.ui.settings.SettingsCard
import com.viswa2k.smsforwarder.ui.settings.SettingsViewModel
import com.viswa2k.smsforwarder.ui.settings.TelegramSection
import com.viswa2k.smsforwarder.ui.settings.openFirst
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme

/**
 * First-run setup as a paged stepper: Welcome, Permissions, Battery, Auto-start, Telegram (optional), Ready.
 * Buttons drive the pager (no swiping); the page index survives rotation; Back goes to the previous page.
 */
@Composable
fun OnboardingPager(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember { AppGraph.from(context) }
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(graph))
    // The Telegram page reuses the Settings Telegram logic (token, Detect, chats).
    val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(graph))
    val health by vm.health.collectAsStateWithLifecycle()
    val oem by vm.oemConfirmed.collectAsStateWithLifecycle()
    val missing by vm.missingPermissions.collectAsStateWithLifecycle()
    val telegram by settingsVm.state.collectAsStateWithLifecycle()
    val snapshot = remember(health, missing, oem, telegram) {
        SetupSnapshot.from(
            health.associate { it.id to it.ok },
            notificationsOk = OnboardingViewModel.NOTIFICATION_PERMISSION.let { it == null || it !in missing },
            oemConfirmed = oem,
            telegramReady = telegram.hasToken && telegram.telegram.isNotEmpty(),
        )
    }

    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(settingsVm) {
        settingsVm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    var page by rememberSaveable { mutableIntStateOf(0) }
    val count = OnboardingSteps.steps.size
    val pager = rememberPagerState(initialPage = page) { count }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(page) {
        if (pager.currentPage != page) {
            if (reduceMotion) pager.scrollToPage(page)
            else pager.animateScrollToPage(page, animationSpec = tween(Transitions.TAB_MS, easing = FastOutSlowInEasing))
        }
    }
    BackHandler(enabled = page > 0) { page = OnboardingSteps.previous(page) }
    val goNext = { page = OnboardingSteps.next(page) }

    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        StepperHeader(page, count, onBack = { page = OnboardingSteps.previous(page) }, reduceMotion = reduceMotion)
        HorizontalPager(
            state = pager,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
        ) { index ->
            when (val step = OnboardingSteps.at(index)) {
                OnbStep.WELCOME -> WelcomePage(goNext)
                OnbStep.PERMISSIONS -> PermissionsPage(vm, missing, snapshot, canNext = OnboardingSteps.canAdvance(step, snapshot), onNext = goNext)
                OnbStep.BATTERY -> BatteryPage(health.firstOrNull { it.id == "battery" }?.fix, snapshot.batteryOk, goNext)
                OnbStep.AUTOSTART -> PageScaffold(R.string.onb_oem_title, R.string.onb_oem_body, bottom = { NextButton(true, goNext) }) {
                    SettingsCard { OemItem(stringResource(R.string.onb_oem_item, Build.MANUFACTURER), oem, vm::setOemConfirmed) }
                }
                OnbStep.TELEGRAM -> TelegramPage(settingsVm, telegram.loaded, telegram.pinSet, snapshot.telegramReady, goNext)
                OnbStep.READY -> ReadyPage(vm, snapshot, onDone)
            }
        }
    }
}

@Composable
private fun StepperHeader(page: Int, count: Int, onBack: () -> Unit, reduceMotion: Boolean) {
    val progress by animateFloatAsState(
        (page + 1f) / count,
        animationSpec = if (reduceMotion) snap() else tween(Transitions.TAB_MS, easing = FastOutSlowInEasing),
        label = "onboardingProgress",
    )
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page > 0) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.onb_back))
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.onb_step_of, page + 1, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Title + body, the page content (scrolls only on small phones) and the bottom actions. */
@Composable
private fun PageScaffold(
    title: Int?,
    body: Int?,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (body != null) Text(stringResource(body), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            content()
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { bottom() }
    }
}

@Composable
private fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(stringResource(R.string.onb_next))
    }
}

@Composable
private fun WelcomePage(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(128.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ForwardToInbox, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                stringResource(R.string.onb_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).height(52.dp)) {
            Text(stringResource(R.string.onb_get_started))
        }
    }
}

@Composable
private fun PermissionsPage(vm: OnboardingViewModel, missing: List<String>, s: SetupSnapshot, canNext: Boolean, onNext: () -> Unit) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        vm.refresh()
    }
    val notification = OnboardingViewModel.NOTIFICATION_PERMISSION
    val action = OnboardingSteps.permAction(missingRequired = missing.any { it != notification }, askedBefore = asked)

    PageScaffold(R.string.onb_perm_title, R.string.onb_perm_body, bottom = {
        when (action) {
            PermAction.REQUEST -> FilledTonalButton(onClick = { launcher.launch(missing.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_allow))
            }
            PermAction.OPEN_SETTINGS -> {
                Text(stringResource(R.string.onb_perm_settings_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                FilledTonalButton(
                    onClick = { openFirst(context, listOf(OemSettings.appDetails(context.packageName))) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onb_open_app_settings)) }
            }
            // Only the optional notification permission is missing: Android may not show its dialog again.
            PermAction.NONE -> if (!s.notificationsOk) {
                TextButton(
                    onClick = { openFirst(context, HealthChecker.statusNotificationIntents(context)) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.onb_show_notification)) }
            }
        }
        NextButton(canNext, onNext)
    }) {
        OutlinedCard(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            PermRow(Icons.Outlined.Sms, R.string.onb_perm_sms, R.string.onb_perm_sms_sub, OnboardingSteps.permStatus(s.smsOk, required = true))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PermRow(Icons.Outlined.Contacts, R.string.onb_perm_contacts, R.string.onb_perm_contacts_sub, OnboardingSteps.permStatus(s.contactsOk, required = true))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PermRow(Icons.Outlined.Notifications, R.string.onb_perm_notif, R.string.onb_perm_notif_sub, OnboardingSteps.permStatus(s.notificationsOk, required = false))
        }
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: Int, sub: Int, status: PermStatus) {
    val extra = ForwarderTheme.extra
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(sub), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        when (status) {
            PermStatus.GRANTED -> ToneChip(stringResource(R.string.onb_status_granted), extra.ok)
            PermStatus.NEEDED -> ToneChip(stringResource(R.string.onb_status_needed), extra.warn)
            PermStatus.OPTIONAL -> ToneChip(stringResource(R.string.onb_status_optional), neutralTone())
        }
    }
}

@Composable
private fun BatteryPage(fix: Intent?, ok: Boolean, onNext: () -> Unit) {
    val context = LocalContext.current
    val extra = ForwarderTheme.extra
    PageScaffold(R.string.onb_battery_title, R.string.onb_battery_body, bottom = { NextButton(true, onNext) }) {
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.onb_battery_row), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                if (ok) ToneChip(stringResource(R.string.settings_status_done), extra.ok)
                else ToneChip(stringResource(R.string.settings_status_needed), extra.warn)
            }
            if (!ok && fix != null) {
                FilledTonalButton(onClick = { openFirst(context, listOf(fix)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.onb_open))
                }
            }
        }
        Text(stringResource(R.string.onb_skip_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun TelegramPage(settingsVm: SettingsViewModel, loaded: Boolean, pinSet: Boolean, ready: Boolean, onNext: () -> Unit) {
    val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()
    PageScaffold(R.string.onb_tg_title, R.string.onb_tg_body, bottom = {
        if (!ready) {
            TextButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.onb_skip_later)) }
        }
        NextButton(true, onNext)
    }) {
        // Onboarding returns when a permission is revoked: with a PIN set, Telegram stays behind it (Settings).
        if (!loaded) {
            Unit // PIN state not read yet: show nothing rather than an unguarded editor
        } else if (pinSet && !unlocked) {
            Text(stringResource(R.string.onb_tg_locked), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TelegramSection(settingsVm, compact = true)
        }
    }
}

@Composable
private fun ReadyPage(vm: OnboardingViewModel, s: SetupSnapshot, onDone: () -> Unit) {
    val masterOn by vm.masterOn.collectAsStateWithLifecycle()
    PageScaffold(
        R.string.onb_finish_title,
        if (masterOn) R.string.onb_finish_body_on else R.string.onb_finish_body_off,
        bottom = {
            if (masterOn) {
                Button(onClick = { if (vm.finish(start = false)) onDone() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.onb_continue))
                }
            } else {
                // Same shared Forwarding.set path as Home's master switch.
                Button(onClick = { if (vm.finish(start = true)) onDone() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.onb_start))
                }
                TextButton(onClick = { if (vm.finish(start = false)) onDone() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.onb_later))
                }
            }
        },
    ) {
        SettingsCard {
            OnboardingSteps.checklist.forEach { step ->
                val done = OnboardingSteps.isComplete(step, s)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (done) ForwarderTheme.extra.ok.fg else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(checkLabel(step)), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun checkLabel(step: OnbStep): Int = when (step) {
    OnbStep.PERMISSIONS -> R.string.onb_check_permissions
    OnbStep.BATTERY -> R.string.onb_check_battery
    OnbStep.AUTOSTART -> R.string.onb_check_autostart
    else -> R.string.onb_check_telegram
}
