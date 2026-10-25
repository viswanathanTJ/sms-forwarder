package com.viswa2k.smsforwarder.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.ui.home.Forwarding
import com.viswa2k.smsforwarder.ui.rules.OneShot
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
    !graph.settings.onboardingDone.first() || !health.smsOk() || !health.contactsOk()
}

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {
    private val checker = HealthChecker(graph.appContext)
    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health
    val oemConfirmed: StateFlow<Boolean> = graph.settings.setupConfirmedOem.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val masterOn: StateFlow<Boolean> = graph.settings.masterOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val once = OneShot()

    private val _missing = MutableStateFlow<List<String>>(emptyList())
    /** Runtime permissions not granted yet (SMS, contacts and, on API 33+, notifications). */
    val missingPermissions: StateFlow<List<String>> = _missing

    fun refresh() {
        _missing.value = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(graph.appContext, it) != PackageManager.PERMISSION_GRANTED
        }
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
        /** Optional: forwarding works without it. */
        val NOTIFICATION_PERMISSION: String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

        val RUNTIME_PERMISSIONS: List<String> = listOfNotNull(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            NOTIFICATION_PERMISSION,
        )

        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OnboardingViewModel(graph) as T
        }
    }
}
