package com.viswa2k.smsforwarder.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.common.SectionTitle

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(AppGraph.from(context)))

    LaunchedEffect(vm) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LifecycleResumeEffect(vm) {
        vm.refreshHealth()
        onPauseOrDispose { }
    }

    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            stringResource(R.string.title_settings),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 4.dp),
        )
        SectionTitle(stringResource(R.string.settings_section_telegram))
        TelegramSection(vm)
        SectionTitle(stringResource(R.string.settings_section_sms))
        SmsSection(vm)
        SectionTitle(stringResource(R.string.settings_section_sources))
        SourcesSection(vm)
        SectionTitle(stringResource(R.string.settings_section_test))
        TestMessageSection(vm)
        SectionTitle(stringResource(R.string.settings_section_health))
        HealthSection(vm)
        SectionTitle(stringResource(R.string.settings_section_device))
        DeviceSection(vm)
        SectionTitle(stringResource(R.string.settings_section_backup))
        ExportImportSection(vm)
        SectionTitle(stringResource(R.string.settings_section_about))
        AboutSection(vm)
    }
}

/** Rounded bordered card used by every Settings section (mock `.card`). */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

/** Compact ghost button (mock `.btn.ghost`). */
@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = modifier)

/** Title + subtitle + trailing content row (destinations, health items). */
@Composable
internal fun ItemRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp)
            if (!subtitle.isNullOrBlank()) Muted(subtitle)
        }
        trailing()
    }
}
