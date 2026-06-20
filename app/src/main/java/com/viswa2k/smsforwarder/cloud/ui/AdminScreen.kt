package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.AuthorizedEmail
import com.viswa2k.smsforwarder.cloud.data.Device
import kotlinx.coroutines.launch

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

    suspend fun reload() { emails = access.listAuthorizedEmails(); devices = vm.fleetDevices() }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Admin") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item { Text("Authorized emails", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newEmail, { newEmail = it }, label = { Text("email") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { access.addAuthorizedEmail(newEmail.trim(), "member", adminEmail); newEmail = ""; reload() } }) { Text("Add") }
                }
            }
            items(emails, key = { it.email }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.email} (${e.role})", Modifier.weight(1f))
                    if (e.role != "admin") TextButton(onClick = { scope.launch { access.removeAuthorizedEmail(e.email); reload() } }) { Text("Remove") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Grant access (reader → source)", style = MaterialTheme.typography.titleMedium) }
            item {
                Column {
                    Text("Reader device:")
                    devices.forEach { d -> FilterChip(selected = readerSel == d.id, onClick = { readerSel = d.id }, label = { Text(d.alias) }) }
                    Spacer(Modifier.height(8.dp))
                    Text("Source device:")
                    devices.forEach { d -> FilterChip(selected = sourceSel == d.id, onClick = { sourceSel = d.id }, label = { Text(d.alias) }) }
                    Button(
                        enabled = readerSel != null && sourceSel != null && readerSel != sourceSel,
                        onClick = { scope.launch { access.grantAccess(readerSel!!, sourceSel!!, adminEmail) } },
                    ) { Text("Grant") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Devices", style = MaterialTheme.typography.titleMedium) }
            items(devices, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.alias}${if (d.revoked) " (revoked)" else ""}", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { access.setDeviceRevoked(d.id, !d.revoked); reload() } }) { Text(if (d.revoked) "Un-revoke" else "Revoke") }
                }
            }
        }
    }
}
