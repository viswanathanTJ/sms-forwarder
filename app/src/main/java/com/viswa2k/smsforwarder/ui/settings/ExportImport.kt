package com.viswa2k.smsforwarder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.Imported

private const val MIME_JSON = "application/json"

/** Export / import of rules + destinations as JSON through the system file picker (SAF). */
@Composable
fun ExportImportSection(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    var includeToken by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { uri ->
        if (uri != null) vm.export(uri, includeToken)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }

    SettingsCard {
        Text(stringResource(R.string.backup_title))
        Muted(stringResource(R.string.backup_body))
        if (state.hasToken) {
            Row(
                Modifier.fillMaxWidth().clickable { includeToken = !includeToken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = includeToken, onCheckedChange = { includeToken = it })
                Text(stringResource(R.string.backup_include_token))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { AppLock.keepForExternalScreen(); exportLauncher.launch("sms-forwarder-settings.json") }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_export))
            }
            OutlinedButton(onClick = { AppLock.keepForExternalScreen(); importLauncher.launch(arrayOf(MIME_JSON, "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_import))
            }
        }
    }

    pending?.let { ImportConfirmDialog(it, onConfirm = vm::applyImport, onDismiss = vm::cancelImport) }
}

@Composable
private fun ImportConfirmDialog(imported: Imported, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var withToken by remember { mutableStateOf(false) }
    val chats = imported.destinations.count { it.channel == Channel.TELEGRAM }
    val numbers = imported.destinations.count { it.channel == Channel.SMS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.import_rules, imported.rules.size, imported.rules.size))
                Text(pluralStringResource(R.plurals.import_chats, chats, chats))
                Text(pluralStringResource(R.plurals.import_numbers, numbers, numbers))
                if (imported.deviceName.isNotBlank()) Muted(stringResource(R.string.import_device, imported.deviceName))
                Text(stringResource(R.string.import_replace_warning), color = MaterialTheme.colorScheme.error)
                if (imported.token != null) {
                    Row(Modifier.clickable { withToken = !withToken }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withToken, onCheckedChange = { withToken = it })
                        Text(stringResource(R.string.import_include_token))
                    }
                } else {
                    Muted(stringResource(R.string.import_no_token))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(withToken) }) { Text(stringResource(R.string.import_replace)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
