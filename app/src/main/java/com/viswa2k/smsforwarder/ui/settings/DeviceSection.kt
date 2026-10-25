package com.viswa2k.smsforwarder.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.BuildConfig
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.PinLock
import com.viswa2k.smsforwarder.update.UpdatePromptDialog

@Composable
fun DeviceSection(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    // Seed the field once the stored name has loaded; later edits stay local until saved.
    LaunchedEffect(state.loaded) { if (state.loaded && name == null) name = state.deviceName }
    val current = name ?: state.deviceName
    var pinDialog by remember { mutableStateOf<PinAction?>(null) }

    SettingsCard {
        OutlinedTextField(
            value = current,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.settings_device_name)) },
            placeholder = { Text(Build.MODEL) },
            supportingText = { Text(stringResource(R.string.settings_device_name_help)) },
            singleLine = true,
            trailingIcon = {
                if (state.loaded && current.trim() != state.deviceName) {
                    TextButton(onClick = { vm.setDeviceName(current) }) { Text(stringResource(R.string.common_save)) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        ItemRow(
            title = stringResource(R.string.pin_title),
            subtitle = stringResource(if (state.pinSet) R.string.pin_on_body else R.string.pin_off_body),
            trailing = {
                if (state.pinSet) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton(stringResource(R.string.pin_change), { pinDialog = PinAction.CHANGE_VERIFY })
                        SmallButton(stringResource(R.string.pin_remove), { pinDialog = PinAction.REMOVE_VERIFY })
                    }
                } else {
                    SmallButton(stringResource(R.string.pin_set), { pinDialog = PinAction.SET })
                }
            },
        )
    }

    val record by vm.pinRecord.collectAsStateWithLifecycle()
    when (pinDialog) {
        PinAction.SET -> SetPinDialog(onSet = { vm.setPin(it); AppLock.unlocked.value = true; pinDialog = null }, onDismiss = { pinDialog = null })
        PinAction.CHANGE_VERIFY, PinAction.REMOVE_VERIFY -> {
            val removing = pinDialog == PinAction.REMOVE_VERIFY
            PinEntryDialog(
                title = stringResource(if (removing) R.string.pin_remove_title else R.string.pin_change_title),
                // One SHA-256 of a few bytes: cheap enough to verify inline.
                verify = { pin -> record?.let { PinLock.verify(pin, it) } ?: false },
                onSuccess = {
                    if (removing) { vm.removePin(); pinDialog = null } else pinDialog = PinAction.SET
                },
                onDismiss = { pinDialog = null },
            )
        }
        null -> Unit
    }
}

private enum class PinAction { SET, CHANGE_VERIFY, REMOVE_VERIFY }

@Composable
fun AboutSection(vm: SettingsViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    SettingsCard {
        ItemRow(
            title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            subtitle = when (update) {
                UpdateStatus.UpToDate -> stringResource(R.string.about_up_to_date)
                UpdateStatus.Failed -> stringResource(R.string.about_check_failed)
                else -> null
            },
            trailing = {
                if (update == UpdateStatus.Checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    SmallButton(stringResource(R.string.about_check), vm::checkForUpdate)
                }
            },
        )
    }
    (update as? UpdateStatus.Available)?.let { UpdatePromptDialog(it.info, onDismiss = vm::dismissUpdate) }
}

