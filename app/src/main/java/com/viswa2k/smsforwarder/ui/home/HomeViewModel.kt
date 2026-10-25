package com.viswa2k.smsforwarder.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.data.db.Counts
import com.viswa2k.smsforwarder.data.db.LogWithSends
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.service.ForwarderService
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.ui.common.DeliveryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RuleRow(val rule: Rule, val destinationLabels: List<String>)

data class RecentRow(
    val id: Long,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val type: MessageType?,
    val state: DeliveryState,
    val skip: SkipReason?,
    val sentTo: List<String>,
)

data class HomeState(
    val masterOn: Boolean = true,
    val deviceName: String = "",
    val counts: Counts = Counts(0, 0, 0),
    val rules: List<RuleRow> = emptyList(),
    val recent: List<RecentRow> = emptyList(),
    /** True until proven otherwise (and until [loaded]) so the setup card does not flash on start. */
    val hasDestinations: Boolean = true,
    val loaded: Boolean = false,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val settingsFlow = combine(graph.settings.masterOn, graph.settings.deviceName) { on, name -> on to name }

    private val rulesFlow = combine(graph.rules.rules, graph.destinations.destinations) { rules, dests ->
        val labels = dests.associate { it.id to it.label }
        rules.sortedWith(compareBy({ it.position }, { it.id }))
            .map { r -> RuleRow(r, r.destinationIds.mapNotNull { labels[it] }) } to dests.isNotEmpty()
    }

    private val recentFlow = combine(graph.logs.recent(RECENT_LIMIT), graph.destinations.destinations) { logs, dests ->
        val labels = dests.associate { it.id to it.label }
        logs.map { it.toRow(labels) }
    }

    val state: StateFlow<HomeState> = combine(
        settingsFlow,
        graph.logs.todayCounts(System.currentTimeMillis()),
        rulesFlow,
        recentFlow,
    ) { (master, device), counts, (rules, hasDestinations), recent ->
        HomeState(master, device, counts, rules, recent, hasDestinations, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health

    /** Re-reads the health checks off the main thread; call on resume. */
    fun refreshHealth() {
        viewModelScope.launch { _health.value = HealthChecker(graph.appContext).items() }
    }

    fun setMaster(on: Boolean, context: Context = graph.appContext) {
        viewModelScope.launch {
            if (on) {
                // Un-pausing never forwards what arrived while paused.
                graph.settings.setCatchUpFloor(System.currentTimeMillis())
                graph.settings.setMasterOn(true)
                ForwarderService.start(context)
            } else {
                graph.settings.setMasterOn(false)
                ForwarderService.stop(context)
            }
        }
    }

    fun setRuleEnabled(id: Long, on: Boolean) {
        viewModelScope.launch { graph.rules.setEnabled(id, on) }
    }

    private fun LogWithSends.toRow(labels: Map<Long, String>): RecentRow {
        val states = sends.map { it.state }
        val state = when {
            log.skip != null || sends.isEmpty() -> DeliveryState.SKIPPED
            OutboxEntity.STATE_PENDING in states -> DeliveryState.RETRYING
            OutboxEntity.STATE_FAILED in states -> DeliveryState.FAILED
            else -> DeliveryState.FORWARDED
        }
        return RecentRow(
            id = log.id,
            sender = log.sender,
            body = log.body,
            receivedAt = log.receivedAt,
            type = MessageType.fromCode(log.type),
            state = state,
            skip = log.skip?.let { SkipReason.fromCode(it) },
            sentTo = sends.mapNotNull { labels[it.destinationId] }.distinct(),
        )
    }

    companion object {
        const val RECENT_LIMIT = 3

        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(graph) as T
        }
    }
}
