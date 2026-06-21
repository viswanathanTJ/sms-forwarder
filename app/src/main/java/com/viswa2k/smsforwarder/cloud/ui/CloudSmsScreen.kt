package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSmsScreen(vm: CloudViewModel, onOpenWatch: () -> Unit, onOpenAdmin: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshMessages(); vm.startRealtime() }
    DisposableEffect(Unit) { onDispose { vm.stopRealtime() } }

    if (showPasswordDialog) {
        SetPasswordDialog(vm) { showPasswordDialog = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud SMS") },
                actions = {
                    TextButton(onClick = onOpenWatch) { Text("Watch") }
                    if (isAdmin) TextButton(onClick = onOpenAdmin) { Text("Admin") }
                    TextButton(onClick = { menuOpen = true }) { Text("More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (vm.hasPasswordProvider()) "Change password" else "Set password") },
                            onClick = { menuOpen = false; showPasswordDialog = true },
                        )
                        if (com.viswa2k.smsforwarder.BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("Send test message") },
                                onClick = { menuOpen = false; vm.sendTestMessage {} },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menuOpen = false; vm.signOut() },
                        )
                    }
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

@Composable
private fun SetPasswordDialog(vm: CloudViewModel, onDismiss: () -> Unit) {
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val email = vm.email.collectAsState().value ?: ""
    val mismatch = pw2.isNotEmpty() && pw1 != pw2

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (vm.hasPasswordProvider()) "Change password" else "Set password") },
        text = {
            Column {
                Text("For $email", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw1, { pw1 = it }, label = { Text("New password (6+)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true,
                    isError = mismatch, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                msg?.let { Spacer(Modifier.height(8.dp)); Text(it, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    enabled = !busy && pw1.length >= 6 && pw1 == pw2,
                    onClick = { busy = true; msg = null; vm.setPassword(pw1) { ok, m -> busy = false; msg = m; done = ok } },
                ) { Text("Save") }
            }
        },
        dismissButton = { if (!done) TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
