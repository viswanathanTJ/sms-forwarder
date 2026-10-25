package com.viswa2k.smsforwarder.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.Destination

@Composable
fun SmsSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val testing by vm.testingIds.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Destination?>(null) }

    // SIM names for rows, only when READ_PHONE_STATE was already granted (no prompt from here).
    LaunchedEffect(Unit) { vm.loadSims() }
    val simPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.loadSims()
        adding = true
    }

    SettingsCard {
        if (state.sms.isEmpty()) Muted(stringResource(R.string.settings_no_numbers), Modifier.padding(vertical = 6.dp))
        state.sms.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val sim = when {
                d.simSubscriptionId == null -> if (sims.size > 1) stringResource(R.string.settings_default_sim) else null
                else -> sims.firstOrNull { it.subscriptionId == d.simSubscriptionId }?.label?.substringBefore(" · ")
            }
            DestinationRow(d, testing = d.id in testing, onTest = { vm.test(d) }, onDelete = { deleting = d }, simLabel = sim)
        }
        OutlinedButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
                // Asked only here, for the SIM picker; a refusal leaves "Default SIM".
                if (granted) { vm.loadSims(); adding = true } else simPermission.launch(Manifest.permission.READ_PHONE_STATE)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.settings_add_number)) }
    }

    if (adding) {
        AddNumberDialog(sims, onAdd = { vm.addDestination(it); adding = false }, onDismiss = { adding = false })
    }
    deleting?.let { d ->
        ConfirmDeleteDialog(d, onConfirm = { vm.deleteDestination(d.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun AddNumberDialog(sims: List<SimOption>, onAdd: (Destination) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var sim by rememberSaveable { mutableStateOf<Int?>(null) }
    val numberOk = NUMBER.matches(number.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_number_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.settings_label)) }, singleLine = true)
                OutlinedTextField(
                    number, { number = it },
                    label = { Text(stringResource(R.string.settings_number)) },
                    singleLine = true,
                    isError = number.isNotBlank() && !numberOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                // Picker only on multi-SIM phones (spec 5.4); otherwise the system default SIM sends.
                if (sims.size > 1) {
                    Text(stringResource(R.string.settings_send_from), style = MaterialTheme.typography.labelLarge)
                    SimChoice(stringResource(R.string.settings_default_sim), sim == null) { sim = null }
                    sims.forEach { s -> SimChoice(s.label, sim == s.subscriptionId) { sim = s.subscriptionId } }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && numberOk,
                onClick = {
                    onAdd(Destination(0, Channel.SMS, label.trim(), number.trim().replace(" ", ""), simSubscriptionId = sim))
                },
            ) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SimChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

private val NUMBER = Regex("^\\+?[0-9][0-9 ]{2,18}[0-9]$")
