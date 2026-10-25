package com.viswa2k.smsforwarder.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme
import com.viswa2k.smsforwarder.ui.theme.Tone

/** Settings root: short grouped list of rows, each opening its own page (`settings/{section}`). */
@Composable
fun SettingsRoot(vm: SettingsViewModel, onOpen: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    SettingsEffects(vm)
    val rows by vm.summaries.collectAsStateWithLifecycle()
    val extra = ForwarderTheme.extra
    val scheme = MaterialTheme.colorScheme
    val primary = Tone(scheme.primary, scheme.primaryContainer)
    val telegram = Tone(extra.telegram, extra.telegram.copy(alpha = 0.16f))

    @Composable
    fun ColumnScope.row(section: SettingsSection, icon: ImageVector, tint: Tone, divider: Boolean = true) {
        SettingsRow(icon, tint, stringResource(section.title), rows[section]) { onOpen(section) }
        if (divider) HorizontalDivider(Modifier.padding(start = 64.dp), color = scheme.outlineVariant)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        Text(
            stringResource(R.string.title_settings),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, top = 12.dp),
        )
        Group(R.string.settings_group_forwarding) {
            row(SettingsSection.TELEGRAM, Icons.AutoMirrored.Outlined.Send, telegram)
            row(SettingsSection.SMS, Icons.Outlined.Sms, extra.transaction)
            row(SettingsSection.SOURCES, Icons.Outlined.MoveToInbox, extra.otp, divider = false)
        }
        Group(R.string.settings_group_tools) {
            row(SettingsSection.TEST, Icons.Outlined.Science, primary)
            row(SettingsSection.HEALTH, Icons.Outlined.MonitorHeart, extra.ok, divider = false)
        }
        Group(R.string.settings_group_phone) {
            row(SettingsSection.AUTOSTART, Icons.Outlined.RocketLaunch, extra.warn)
            row(SettingsSection.NOTIFICATION, Icons.Outlined.Notifications, extra.missedCall)
            row(SettingsSection.DEVICE, Icons.Outlined.PhoneAndroid, extra.neutral)
            row(SettingsSection.PIN, Icons.Outlined.Lock, primary, divider = false)
        }
        Group(R.string.settings_group_data) {
            row(SettingsSection.BACKUP, Icons.Outlined.ImportExport, extra.transaction)
            row(SettingsSection.ABOUT, Icons.Outlined.Info, extra.neutral, divider = false)
        }
    }
}

@Composable
private fun Group(label: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(label).uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Column(content = content) }
}

/** One sub-page: the section's existing card(s) under a top app bar. */
@Composable
fun SettingsSectionPage(section: SettingsSection, vm: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsEffects(vm)
    SettingsSubPage(stringResource(section.title), onBack, modifier) {
        when (section) {
            SettingsSection.TELEGRAM -> TelegramSection(vm)
            SettingsSection.SMS -> SmsSection(vm)
            SettingsSection.SOURCES -> SourcesSection(vm)
            SettingsSection.TEST -> TestMessageSection(vm)
            SettingsSection.HEALTH -> HealthSection(vm)
            SettingsSection.AUTOSTART -> AutoStartSection(vm)
            SettingsSection.NOTIFICATION -> StatusNotificationSection(vm)
            SettingsSection.DEVICE -> DeviceNameSection(vm)
            SettingsSection.PIN -> PinSection(vm)
            SettingsSection.BACKUP -> ExportImportSection(vm)
            SettingsSection.ABOUT -> AboutSection(vm)
        }
    }
}

/** Battery unrestricted (health item with Fix) + the per-brand auto-start guide (Task 17). */
@Composable
private fun AutoStartSection(vm: SettingsViewModel) {
    val items by vm.health.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsCard { HealthList(items, state.oemConfirmed, vm::setOemConfirmed, ids = setOf("battery")) }
    Muted(stringResource(R.string.settings_autostart_body), Modifier.padding(horizontal = 4.dp))
    SettingsCard { OemItem(stringResource(R.string.onb_oem_item, Build.MANUFACTURER), state.oemConfirmed, vm::setOemConfirmed) }
}
