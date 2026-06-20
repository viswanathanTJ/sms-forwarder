package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.Device
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var myDeviceId by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf<List<Device>>(emptyList()) }
    var watchedNotify by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(Unit) {
        myDeviceId = vm.myDeviceId()
        val sources = vm.accessRepository().allowedSources(myDeviceId).toSet()
        allowed = vm.fleetDevices().filter { it.id in sources }
        watchedNotify = vm.accessRepository().listSubscriptions(myDeviceId).associate { it.sourceDeviceId to it.notify }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Watch devices") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            items(allowed, key = { it.id }) { dev ->
                val sourceId = dev.id
                val watched = watchedNotify.containsKey(sourceId)
                val notify = watchedNotify[sourceId] ?: true
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dev.alias, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = watched, onCheckedChange = { on ->
                                scope.launch {
                                    if (on) vm.accessRepository().subscribe(myDeviceId, sourceId, true)
                                    else vm.accessRepository().unsubscribe(myDeviceId, sourceId)
                                    watchedNotify = if (on) watchedNotify + (sourceId to true) else watchedNotify - sourceId
                                }
                            })
                        }
                        if (watched) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Notify", Modifier.weight(1f))
                                Switch(checked = notify, onCheckedChange = { on ->
                                    scope.launch {
                                        vm.accessRepository().setNotify(myDeviceId, sourceId, on)
                                        watchedNotify = watchedNotify + (sourceId to on)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
