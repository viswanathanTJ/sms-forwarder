package com.viswa2k.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.SourceFilter
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.ui.common.Pill
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme

/**
 * Sources card (spec addendum §1): SMS is always on; WhatsApp is a toggle (off by default). Switching it on
 * without Notification access opens the system screen. The whole Settings tab is PIN-gated already.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val enabled by vm.whatsAppEnabled.collectAsStateWithLifecycle()
    val types by vm.whatsAppTypes.collectAsStateWithLifecycle()
    val access by vm.notifAccess.collectAsStateWithLifecycle()
    fun openAccess() = openFirst(context, HealthChecker.notificationAccessIntents(context))

    SettingsCard {
        ItemRow(
            title = stringResource(R.string.sources_sms),
            subtitle = stringResource(R.string.sources_sms_hint),
            trailing = { Pill(stringResource(R.string.sources_always_on), ForwarderTheme.extra.ok) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ItemRow(
            title = stringResource(R.string.sources_whatsapp),
            subtitle = stringResource(
                when {
                    enabled != true -> R.string.sources_whatsapp_hint
                    access -> R.string.sources_access_allowed
                    else -> R.string.sources_access_missing
                },
            ),
            trailing = {
                Switch(
                    checked = enabled == true,
                    enabled = enabled != null,
                    onCheckedChange = { on ->
                        vm.setWhatsApp(on)
                        if (on && !access) openAccess()
                    },
                )
            },
        )
        Muted(stringResource(R.string.sources_wa_types), Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceFilter.SELECTABLE.forEach { t ->
                FilterChip(
                    selected = t in types,
                    onClick = { vm.toggleWhatsAppType(t) },
                    label = { Text(t.label) },
                )
            }
        }
        if (enabled == true && !access) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted(stringResource(R.string.sources_access_steps), Modifier.weight(1f).padding(end = 8.dp))
                SmallButton(stringResource(R.string.sources_allow), { openAccess() })
            }
        }
    }
}
