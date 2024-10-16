package com.viswa2k.smsforwarder.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.SmsForwarderService
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.ui.screen.SettingsViewModel
import com.viswa2k.smsforwarder.ui.screen.SettingsViewModelFactory

@Composable
fun SettingsScreen(userPreferences: UserPreferences, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(userPreferences))

    // Collect states from the ViewModel
    val smsForwardServiceEnabled by viewModel.smsForwardServiceEnabled.collectAsState()
    val isSkipContacts by viewModel.isSkipContacts.collectAsState()
    val isBySmsEnabled by viewModel.isBySmsEnabled.collectAsState()
    val isByTelegramEnabled by viewModel.isByTelegramEnabled.collectAsState()
    val deviceAlias by viewModel.deviceAlias.collectAsState()
    val globalMessageFormat by viewModel.globalMessageFormat.collectAsState()
    val smsMessageFormat by viewModel.smsMessageFormat.collectAsState()
    val telegramMessageFormat by viewModel.telegramMessageFormat.collectAsState()
    val smsToNumber by viewModel.smsToNumber.collectAsState()
    val telegramApiKey by viewModel.telegramApiKey.collectAsState()
    val telegramUserIds by viewModel.telegramUserIds.collectAsState()

    // Use LazyColumn for scrolling
    LazyColumn(modifier = modifier.padding(16.dp)) {
        item {
            Text("SMS Forwarder", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(16.dp))

            // Configuration Card
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Service", modifier = Modifier.weight(1f))
                        Switch(
                            checked = smsForwardServiceEnabled,
                            onCheckedChange = { viewModel.updateSmsForwardServiceEnabled(it) }
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Skip Contacts", modifier = Modifier.weight(1f))
                        Switch(
                            checked = isSkipContacts,
                            onCheckedChange = { viewModel.updateIsSkipContacts(it) }
                        )
                    }

                    OutlinedTextField(
                        value = deviceAlias,
                        onValueChange = { viewModel.updateDeviceAlias(it) },
                        label = { Text("Device alias") },
                        shape = MaterialTheme.shapes.medium,
                        placeholder = { Text("Android") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )

                    OutlinedTextField(
                        value = globalMessageFormat,
                        onValueChange = { viewModel.updateGlobalMessageFormat(it) },
                        label = { Text("Global message format") },
                        shape = MaterialTheme.shapes.medium,
                        placeholder = { Text("%m") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Text(
                        text = "Use: %s - sender, %r - receiver, %m - message",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }

            // Forward as SMS Card
            AnimatedVisibility(smsForwardServiceEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Forward as SMS", style = MaterialTheme.typography.titleMedium)

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Forward as SMS", modifier = Modifier.weight(1f))
                            Switch(
                                checked = isBySmsEnabled,
                                onCheckedChange = { viewModel.updateIsBySmsEnabled(it) }
                            )
                        }

                        AnimatedVisibility(visible = isBySmsEnabled) {
                            Column {
                                OutlinedTextField(
                                    value = smsToNumber,
                                    onValueChange = { viewModel.updateSmsToNumber(it) },
                                    label = { Text("SMS to Number") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                OutlinedTextField(
                                    value = smsMessageFormat,
                                    onValueChange = { viewModel.updateSmsMessageFormat(it) },
                                    label = { Text("SMS message format") },
                                    shape = MaterialTheme.shapes.medium,
                                    placeholder = { Text("%m") },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                Text(
                                    text = "By default Global message format used",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Telegram Card
            AnimatedVisibility(smsForwardServiceEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Send in Telegram", style = MaterialTheme.typography.titleMedium)

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Send in Telegram", modifier = Modifier.weight(1f))
                            Switch(
                                checked = isByTelegramEnabled,
                                onCheckedChange = { viewModel.updateIsByTelegramEnabled(it) }
                            )
                        }

                        AnimatedVisibility(visible = isByTelegramEnabled) {
                            Column {
                                OutlinedTextField(
                                    value = telegramApiKey,
                                    onValueChange = { viewModel.updateTelegramApiKey(it) },
                                    label = { Text("Telegram API Key") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                OutlinedTextField(
                                    value = telegramUserIds,
                                    onValueChange = { viewModel.updateTelegramUserIds(it) },
                                    label = { Text("Telegram User IDs") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                OutlinedTextField(
                                    value = telegramMessageFormat,
                                    onValueChange = { viewModel.updateTelegramMessageFormat(it) },
                                    label = { Text("Telegram message format") },
                                    placeholder = { Text("%m") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                Text(
                                    text = "By default Global message format used",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Save Settings Button
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        val validationErrors = mutableListOf<String>()

                        // Validation for smsForwardServiceEnabled
                        if (smsForwardServiceEnabled && !isBySmsEnabled && !isByTelegramEnabled) {
                            validationErrors.add("Either 'Forward as SMS' or 'Send in Telegram' must be enabled.")
                        }

                        // Validation for isBySmsEnabled
                        if (smsForwardServiceEnabled && isBySmsEnabled && smsToNumber.isBlank()) {
                            validationErrors.add("SMS to Number must be provided.")
                        }

                        // Validation for isByTelegramEnabled
                        if (smsForwardServiceEnabled && isByTelegramEnabled) {
                            if (telegramApiKey.isBlank() || telegramUserIds.isBlank()) {
                                validationErrors.add("Telegram API Key and User IDs must not be empty.")
                            }
                        }

                        if (validationErrors.isNotEmpty()) {
                            Toast.makeText(context, validationErrors.joinToString("\n"), Toast.LENGTH_LONG).show()
                        } else {
                            // Save settings if validation passes
                            viewModel.saveSettings()

                            // Start or stop the SmsForwarderService based on the service toggle
                            val serviceIntent = Intent(context, SmsForwarderService::class.java).apply {
                                putExtra("SMS_FORWARD_SERVICE_ENABLED", smsForwardServiceEnabled)
                            }

                            if (smsForwardServiceEnabled) {
                                ContextCompat.startForegroundService(context, serviceIntent) // Start the service
                            } else {
                                context.stopService(serviceIntent) // Stop the service
                            }

                            Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .height(56.dp)
                        .padding(horizontal = 32.dp)
                ) {
                    Text("Save Settings", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                }
            }
        }
    }
}
