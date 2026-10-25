package com.viswa2k.smsforwarder.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.service.OemSettings
import com.viswa2k.smsforwarder.ui.common.Pill
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme

@Composable
fun HealthSection(vm: SettingsViewModel) {
    val items by vm.health.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsCard {
        HealthList(items, oemConfirmed = state.oemConfirmed, onOemDone = vm::setOemConfirmed)
    }
}

/** The health checklist (Settings card and onboarding). Each failing item has a Fix that opens its screen. */
@Composable
fun HealthList(items: List<HealthItem>, oemConfirmed: Boolean, onOemDone: (Boolean) -> Unit, ids: Set<String>? = null) {
    val context = LocalContext.current
    val shown = if (ids == null) items else items.filter { it.id in ids }
    if (shown.isEmpty()) Muted(stringResource(R.string.health_checking))
    shown.forEachIndexed { i, item ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (item.id == "oem") {
            OemItem(item.title, oemConfirmed, onOemDone)
        } else {
            ItemRow(
                title = item.title,
                subtitle = if (item.ok) null else healthHint(item.id),
                leading = { StatusIcon(item.ok) },
                trailing = {
                    val fix = item.fix
                    when {
                        item.ok -> Pill(stringResource(R.string.health_ok), ForwarderTheme.extra.ok)
                        fix != null -> SmallButton(stringResource(R.string.health_fix), { openFirst(context, listOf(fix)) })
                        else -> Pill(stringResource(R.string.health_problem), ForwarderTheme.extra.warn)
                    }
                },
            )
        }
    }
}

@Composable
private fun StatusIcon(ok: Boolean) {
    val extra = ForwarderTheme.extra
    Icon(
        if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
        contentDescription = null,
        tint = if (ok) extra.ok.fg else extra.warn.fg,
        modifier = Modifier.size(22.dp),
    )
}

/** Brand auto-start step: deep link (tries vendor screens, then App info), "what to tap" hint, and a Done tick. */
@Composable
internal fun OemItem(title: String, confirmed: Boolean, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(confirmed)
            Text(title, modifier = Modifier.weight(1f).padding(start = 10.dp))
            SmallButton(stringResource(R.string.health_open), { openFirst(context, OemSettings.intents(Build.MANUFACTURER)) })
        }
        Muted(OemSettings.hint(Build.MANUFACTURER), Modifier.padding(start = 32.dp, top = 4.dp))
        Row(
            Modifier.padding(start = 20.dp).clickable { onDone(!confirmed) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = confirmed, onCheckedChange = onDone)
            Text(stringResource(R.string.health_oem_done))
        }
    }
}

@Composable
private fun healthHint(id: String): String? = when (id) {
    "sms" -> stringResource(R.string.health_hint_sms)
    "contacts" -> stringResource(R.string.health_hint_contacts)
    "notif" -> stringResource(R.string.health_hint_notif)
    "battery" -> stringResource(R.string.health_hint_battery)
    "classifier" -> stringResource(R.string.health_hint_classifier)
    else -> null
}

/** Starts the first intent that resolves; vendor screens differ per ROM version. */
internal fun openFirst(context: Context, intents: List<Intent>): Boolean {
    for (intent in intents) {
        try {
            AppLock.keepForExternalScreen()
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) { // ActivityNotFoundException, SecurityException (non-exported vendor screen)
        }
    }
    Toast.makeText(context, context.getString(R.string.health_open_failed), Toast.LENGTH_SHORT).show()
    return false
}
