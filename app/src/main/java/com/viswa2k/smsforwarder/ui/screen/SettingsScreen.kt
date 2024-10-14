package com.viswa2k.smsforwarder.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.UserPreferences
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(userPreferences: UserPreferences, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Collect current preferences as state
    val smsServiceEnabled by userPreferences.isSmsServiceEnabled.collectAsState(initial = false)
    val telegramServiceEnabled by userPreferences.isTelegramServiceEnabled.collectAsState(initial = false)

    // Use remember to persist the state
    var smsToNumber by remember { mutableStateOf("") }
    var telegramApiKey by remember { mutableStateOf("") }
    var telegramUserIds by remember { mutableStateOf("") }

    // Collect SMS number
    LaunchedEffect(Unit) {
        userPreferences.smsToNumber.collect { number ->
            smsToNumber = number ?: ""
        }
    }

    // Collect Telegram API Key
    LaunchedEffect(Unit) {
        userPreferences.telegramApiKey.collect { apiKey ->
            Log.d("UserPreferences", "API key: $apiKey")
            telegramApiKey = apiKey ?: ""
        }
    }

    // Collect Telegram User IDs
    LaunchedEffect(Unit) {
        userPreferences.telegramUserIds.collect { userIds ->
            telegramUserIds = userIds ?: ""
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text("SMS Forwarder", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(16.dp))

        // SMS Service Toggle
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable SMS Service", modifier = Modifier.weight(1f)) // Align left
            Switch(
                checked = smsServiceEnabled,
                onCheckedChange = { isChecked ->
                    coroutineScope.launch {
                        userPreferences.saveSmsServiceEnabled(isChecked)
                    }
                }
            )
        }

        // Show SMS to Number TextField if SMS Service is enabled
        AnimatedVisibility(visible = smsServiceEnabled) {
            TextField(
                value = smsToNumber,
                onValueChange = { newNumber ->
                    // Validate number length (for example, restrict to 15 digits)
                    if (newNumber.length <= 15) {
                        smsToNumber = newNumber
                    }
                },
                label = { Text("SMS to Number") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp)
            )
        }

        // Telegram Service Toggle
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable Telegram Service", modifier = Modifier.weight(1f)) // Align left
            Switch(
                checked = telegramServiceEnabled,
                onCheckedChange = { isChecked ->
                    coroutineScope.launch {
                        userPreferences.saveTelegramServiceEnabled(isChecked)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Show Telegram API Key TextField if Telegram Service is enabled
        AnimatedVisibility(visible = telegramServiceEnabled) {
            TextField(
                value = telegramApiKey,
                onValueChange = { telegramApiKey = it },
                label = { Text("Telegram API Key") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp)
            )
        }

        // Show Telegram User IDs TextField if Telegram Service is enabled
        AnimatedVisibility(visible = telegramServiceEnabled) {
            TextField(
                value = telegramUserIds,
                onValueChange = { telegramUserIds = it },
                label = { Text("Telegram User IDs") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp)
            )
        }

        // Center Save Settings Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center // Center button
        ) {
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        userPreferences.saveSmsToNumber(smsToNumber)
                        userPreferences.saveTelegramApiKey(telegramApiKey)
                        userPreferences.saveTelegramUserIds(telegramUserIds)
                        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Text("Save Settings")
            }
        }
    }
}
