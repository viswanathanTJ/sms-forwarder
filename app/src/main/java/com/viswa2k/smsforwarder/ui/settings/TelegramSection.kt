package com.viswa2k.smsforwarder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme

@Composable
fun TelegramSection(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val testing by vm.testingIds.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Destination?>(null) }

    SettingsCard {
        TokenField(hasToken = state.hasToken, onSave = vm::saveToken)
        Text(
            stringResource(R.string.settings_chats),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        if (state.telegram.isEmpty()) Muted(stringResource(R.string.settings_no_chats), Modifier.padding(vertical = 6.dp))
        state.telegram.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DestinationRow(d, testing = d.id in testing, onTest = { vm.test(d) }, onDelete = { deleting = d })
        }
        OutlinedButton(onClick = { vm.resetDetect(); adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_add_chat))
        }
        if (state.telegram.isNotEmpty()) {
            AlertsPicker(state.telegram, state.alertsDestinationId, vm::setAlertsDestination)
        }
    }

    if (adding) {
        AddChatDialog(vm, onDismiss = { adding = false })
    }
    deleting?.let { d ->
        ConfirmDeleteDialog(d, onConfirm = { vm.deleteDestination(d.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun TokenField(hasToken: Boolean, onSave: (String) -> Boolean) {
    // Plain remember: the typed token must not land in the saved-instance-state Bundle.
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; invalid = false },
        label = { Text(stringResource(R.string.settings_bot_token)) },
        // Never decrypted into the UI: an existing token shows only as a masked placeholder.
        placeholder = { if (hasToken) Text(stringResource(R.string.settings_token_saved)) else Text(stringResource(R.string.settings_token_hint)) },
        singleLine = true,
        isError = invalid,
        supportingText = when {
            invalid -> ({ Text(stringResource(R.string.settings_token_invalid)) })
            hasToken -> ({ Text(stringResource(R.string.settings_token_saved_help)) })
            else -> null
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            if (text.isNotBlank()) {
                TextButton(onClick = { if (onSave(text)) text = "" else invalid = true }) { Text(stringResource(R.string.common_save)) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One destination row (mock `.dest`): icon, label, address, Test, delete. */
@Composable
internal fun DestinationRow(d: Destination, testing: Boolean, onTest: () -> Unit, onDelete: () -> Unit, simLabel: String? = null) {
    val subtitle = when (d.channel) {
        Channel.TELEGRAM -> stringResource(R.string.settings_chat_sub, d.address) +
            (d.topicId?.let { " · " + stringResource(R.string.settings_topic_sub, it) } ?: "")
        Channel.SMS -> d.address + (simLabel?.let { " · $it" } ?: "")
    }
    ItemRow(
        title = d.label,
        subtitle = subtitle,
        leading = {
            if (d.channel == Channel.TELEGRAM) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = ForwarderTheme.extra.telegram, modifier = Modifier.size(20.dp))
            } else {
                Text("💬", fontSize = 18.sp)
            }
        },
        trailing = {
            if (testing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                SmallButton(stringResource(R.string.settings_test), onTest)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_delete_named, d.label))
            }
        },
    )
}

@Composable
internal fun ConfirmDeleteDialog(d: Destination, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_title, d.label)) },
        text = { Text(stringResource(R.string.settings_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun AlertsPicker(chats: List<Destination>, selected: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = chats.firstOrNull { it.id == selected }
    val label = current?.label ?: stringResource(R.string.settings_alerts_default, chats.first().label)
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.settings_alerts_title), fontSize = 14.5.sp)
        Muted(stringResource(R.string.settings_alerts_body))
        Box {
            Row(
                Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.settings_alerts_title))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alerts_default, chats.first().label)) },
                    onClick = { onSelect(null); open = false },
                )
                chats.forEach { c ->
                    DropdownMenuItem(text = { Text(c.label) }, onClick = { onSelect(c.id); open = false })
                }
            }
        }
    }
}

@Composable
private fun AddChatDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val detect by vm.detect.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("") }
    var chatId by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    val chatOk = CHAT_ID.matches(chatId.trim())
    val topicOk = topic.isBlank() || topic.trim().toLongOrNull()?.let { it > 0 } == true
    LaunchedEffect(Unit) { vm.resetDetect() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_chat_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Muted(stringResource(R.string.settings_add_chat_help))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::detectChats, enabled = detect != DetectState.Loading) {
                        Text(stringResource(R.string.settings_detect))
                    }
                    if (detect == DetectState.Loading) {
                        CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                }
                when (val d = detect) {
                    is DetectState.Error -> Text(d.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    is DetectState.Found -> d.chats.forEach { c ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                chatId = c.chatId.toString()
                                topic = c.topicId?.toString() ?: ""
                                if (label.isBlank()) label = c.title
                            }.padding(vertical = 6.dp),
                        ) {
                            Text(c.title, fontWeight = FontWeight.SemiBold)
                            Muted(
                                stringResource(R.string.settings_chat_sub, c.chatId.toString()) +
                                    (c.topicId?.let { " · " + stringResource(R.string.settings_topic_sub, it) } ?: ""),
                            )
                        }
                    }
                    else -> Unit
                }
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.settings_label)) }, singleLine = true)
                OutlinedTextField(
                    chatId, { chatId = it.trim() },
                    label = { Text(stringResource(R.string.settings_chat_id)) },
                    singleLine = true,
                    isError = chatId.isNotEmpty() && !chatOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(
                    topic, { topic = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_topic_id)) },
                    singleLine = true,
                    isError = !topicOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && chatOk && topicOk,
                onClick = {
                    vm.addDestination(
                        Destination(
                            id = 0, channel = Channel.TELEGRAM, label = label.trim(), address = chatId.trim(),
                            topicId = topic.trim().toLongOrNull(),
                        ),
                    )
                    onDismiss()
                },
            ) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Numeric chat id (groups are negative) or a public `@channelusername`. */
private val CHAT_ID = Regex("^(-?[0-9]{1,20}|@[A-Za-z0-9_]{5,32})$")
