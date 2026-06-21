package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSmsScreen(vm: CloudViewModel, onOpenWatch: () -> Unit, onOpenAdmin: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()

    LaunchedEffect(Unit) { vm.refreshMessages(); vm.startRealtime() }
    DisposableEffect(Unit) { onDispose { vm.stopRealtime() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud SMS") },
                actions = {
                    TextButton(onClick = onOpenWatch) { Text("Watch") }
                    if (isAdmin) TextButton(onClick = onOpenAdmin) { Text("Admin") }
                    TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No cloud messages yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cloud SMS shows incoming texts from your other devices, end-to-end encrypted. " +
                        "Enable \"Upload to cloud\" on a device to send its SMS here, and use Watch to follow " +
                        "the devices you're allowed to read.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                items(messages, key = { it.id }) { m ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${m.sourceAlias} • ${m.sender}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(m.body, style = MaterialTheme.typography.bodyLarge)
                            if (isAdmin) {
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { vm.deleteMessage(m.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
