package com.viswa2k.smsforwarder.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.ui.home.Forwarding
import com.viswa2k.smsforwarder.ui.rules.OneShot
import com.viswa2k.smsforwarder.ui.settings.HealthList
import com.viswa2k.smsforwarder.ui.settings.OemItem
import com.viswa2k.smsforwarder.ui.settings.SettingsCard
import com.viswa2k.smsforwarder.ui.settings.openFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True when first-run setup must be shown: never finished, or a required permission is missing. */
suspend fun needsOnboarding(graph: AppGraph): Boolean = withContext(Dispatchers.IO) {
    val health = HealthChecker(graph.appContext)
    !graph.settings.onboardingDone.first() || !health.smsOk() || !health.contactsOk() || !health.notificationsOk()
}

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {
    private val checker = HealthChecker(graph.appContext)
    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health
    val oemConfirmed: StateFlow<Boolean> = graph.settings.setupConfirmedOem.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val masterOn: StateFlow<Boolean> = graph.settings.masterOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val once = OneShot()

    fun refresh() {
        viewModelScope.launch { _health.value = withContext(Dispatchers.Default) { checker.items() } }
    }

    fun setOemConfirmed(done: Boolean) {
        graph.appScope.launch { graph.settings.setSetupConfirmedOem(done) }
    }

    /** Ends setup; [start] switches forwarding on through the same path as Home's master switch. */
    fun finish(start: Boolean): Boolean {
        if (!once.tryRun()) return false
        if (start) Forwarding.set(graph, true)
        graph.appScope.launch { graph.settings.setOnboardingDone(true) }
        return true
    }

    companion object {
        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OnboardingViewModel(graph) as T
        }
    }
}

private enum class Step { PERMISSIONS, BATTERY, OEM, FINISH }

private val PERMISSION_IDS = setOf("sms", "contacts", "notif")

@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(AppGraph.from(context)))
    val health by vm.health.collectAsStateWithLifecycle()
    val oem by vm.oemConfirmed.collectAsStateWithLifecycle()
    val masterOn by vm.masterOn.collectAsStateWithLifecycle()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = Step.entries[stepIndex]

    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onb_welcome), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { (stepIndex + 1f) / Step.entries.size }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.onb_step_of, stepIndex + 1, Step.entries.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )

        when (step) {
            Step.PERMISSIONS -> {
                StepHeader(R.string.onb_perm_title, R.string.onb_perm_body)
                SettingsCard { HealthList(health, oem, vm::setOemConfirmed, ids = PERMISSION_IDS) }
                val missing = health.any { it.id in PERMISSION_IDS && !it.ok }
                if (missing) {
                    Button(onClick = { permissions.launch(runtimePermissions()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onb_grant))
                    }
                }
            }
            Step.BATTERY -> {
                StepHeader(R.string.onb_battery_title, R.string.onb_battery_body)
                SettingsCard { HealthList(health, oem, vm::setOemConfirmed, ids = setOf("battery")) }
                val battery = health.firstOrNull { it.id == "battery" }
                if (battery != null && !battery.ok && battery.fix != null) {
                    Button(onClick = { openFirst(context, listOf(battery.fix)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onb_battery_action))
                    }
                }
            }
            Step.OEM -> {
                StepHeader(R.string.onb_oem_title, R.string.onb_oem_body)
                SettingsCard { OemItem(stringResource(R.string.onb_oem_item, Build.MANUFACTURER), oem, vm::setOemConfirmed) }
            }
            Step.FINISH -> {
                StepHeader(R.string.onb_finish_title, if (masterOn) R.string.onb_finish_body_on else R.string.onb_finish_body_off)
                SettingsCard { HealthList(health, oem, vm::setOemConfirmed) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (stepIndex > 0) TextButton(onClick = { stepIndex-- }) { Text(stringResource(R.string.onb_back)) } else Spacer(Modifier)
            if (step != Step.FINISH) {
                Button(onClick = { stepIndex++ }) { Text(stringResource(R.string.onb_next)) }
            } else if (masterOn) {
                Button(onClick = { if (vm.finish(start = false)) onDone() }) { Text(stringResource(R.string.onb_continue)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (vm.finish(start = false)) onDone() }) { Text(stringResource(R.string.onb_not_now)) }
                    Button(onClick = { if (vm.finish(start = true)) onDone() }) { Text(stringResource(R.string.onb_start)) }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(title: Int, body: Int) {
    Column {
        Text(stringResource(title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(body), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
