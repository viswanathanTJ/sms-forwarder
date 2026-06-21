package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.AccessRequest
import com.viswa2k.smsforwarder.cloud.data.AuthorizedEmail
import com.viswa2k.smsforwarder.cloud.data.Device
import kotlinx.coroutines.launch

/** Disambiguates same-named devices with a short id, and tags the current device. */
private fun chipLabel(d: Device, myDeviceId: String): String =
    d.alias.ifBlank { "(unnamed)" } + " ·" + d.id.take(4) + if (d.id == myDeviceId) " ★" else ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val access = vm.accessRepository()
    val adminEmail = vm.email.collectAsState().value ?: ""
    var emails by remember { mutableStateOf<List<AuthorizedEmail>>(emptyList()) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var newEmail by remember { mutableStateOf("") }
    var readerSel by remember { mutableStateOf<String?>(null) }
    var sourceSel by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<AccessRequest>>(emptyList()) }
    var myDeviceId by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // Reads never throw out of the coroutine — a Firestore/permission error shows a snackbar
    // instead of crashing the screen.
    suspend fun reload() {
        runCatching {
            myDeviceId = vm.myDeviceId()
            emails = access.listAuthorizedEmails()
            devices = vm.fleetDevices()
            requests = access.listAccessRequests()
        }.onFailure { snackbar.showSnackbar("Couldn't load admin data: ${it.message ?: "error"}") }
    }
    // Runs an admin action safely, then refreshes; surfaces any failure as a snackbar.
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { snackbar.showSnackbar(it.message ?: "Action failed") }
        reload()
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Admin") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            // Overview
            item {
                ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cloud SMS", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Cloud SMS securely syncs incoming text messages between your devices. " +
                                "Each message is end-to-end encrypted on the sending device and can only be read " +
                                "by devices you authorize — the server never sees the plaintext. As administrator " +
                                "you control who can sign in and which device may read which.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // Status
            item {
                val thisAlias = devices.firstOrNull { it.id == myDeviceId }?.alias
                    ?: myDeviceId.take(8).ifBlank { "—" }
                ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Signed in: $adminEmail  ·  Administrator", style = MaterialTheme.typography.bodyMedium)
                        Text("This device: $thisAlias", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Devices: ${devices.size}   ·   Authorized: ${emails.size}   ·   Pending: ${requests.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Text("Manage", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            if (requests.isNotEmpty()) {
                item { Text("Pending access requests", style = MaterialTheme.typography.titleMedium) }
                items(requests, key = { it.email }) { req ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(req.email)
                            if (req.displayName.isNotBlank()) {
                                Text(req.displayName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { act { access.approveRequest(req.email, adminEmail) } }) { Text("Approve") }
                        TextButton(onClick = { act { access.denyRequest(req.email) } }) { Text("Deny") }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            item { Text("Authorized emails", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newEmail, { newEmail = it }, label = { Text("email") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { act { access.addAuthorizedEmail(newEmail.trim(), "member", adminEmail); newEmail = "" } }) { Text("Add") }
                }
            }
            items(emails, key = { it.email }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.email} (${e.role})", Modifier.weight(1f))
                    if (e.role != "admin") TextButton(onClick = { act { access.removeAuthorizedEmail(e.email) } }) { Text("Remove") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Grant access (reader → source)", style = MaterialTheme.typography.titleMedium) }
            item {
                Column {
                    Text("Reader device:")
                    devices.forEach { d -> FilterChip(selected = readerSel == d.id, onClick = { readerSel = d.id }, label = { Text(chipLabel(d, myDeviceId)) }) }
                    Spacer(Modifier.height(8.dp))
                    Text("Source device:")
                    devices.forEach { d -> FilterChip(selected = sourceSel == d.id, onClick = { sourceSel = d.id }, label = { Text(chipLabel(d, myDeviceId)) }) }
                    Button(
                        enabled = readerSel != null && sourceSel != null && readerSel != sourceSel,
                        onClick = { act { access.grantAccess(readerSel!!, sourceSel!!, adminEmail); snackbar.showSnackbar("Access granted") } },
                    ) { Text("Grant") }
                }
            }

            item {
                Spacer(Modifier.height(16.dp)); Text("Devices", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Each install is a separate device. Same name with a different id = a different/old install — remove stale ones.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(devices, key = { it.id }) { d ->
                val isThis = d.id == myDeviceId
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(d.alias.ifBlank { "(unnamed)" })
                                if (isThis) append("  •  This device")
                                if (d.revoked) append("  •  revoked")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text("id ${d.id.take(8)}  ·  ${d.ownerEmail}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { act { access.setDeviceRevoked(d.id, !d.revoked) } }) { Text(if (d.revoked) "Un-revoke" else "Revoke") }
                    if (!isThis) {
                        TextButton(onClick = { act { access.removeDeviceAndData(d.id) } }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
