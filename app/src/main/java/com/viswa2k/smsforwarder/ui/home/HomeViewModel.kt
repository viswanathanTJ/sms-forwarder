package com.viswa2k.smsforwarder.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.core.RuleEngine
import com.viswa2k.smsforwarder.service.ForwarderService
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.ui.log.HomeCounts
import com.viswa2k.smsforwarder.ui.log.LogRowData
import com.viswa2k.smsforwarder.ui.log.deliveryState
import com.viswa2k.smsforwarder.ui.log.homeCounts
import com.viswa2k.smsforwarder.ui.log.startOfDay
import com.viswa2k.smsforwarder.ui.log.logRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The one path that switches forwarding on or off (Home master switch, onboarding "Start forwarding"). */
object Forwarding {
    private val lock = Mutex()

    // Runs on the app scope (a screen leaving mid-toggle cannot half-apply) and in order.
    fun set(graph: AppGraph, on: Boolean) {
        val context = graph.appContext
        graph.appScope.launch {
            lock.withLock {
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
    }
}

data class RuleRow(val rule: Rule, val destinationLabels: List<String>)

data class HomeState(
    val masterOn: Boolean = true,
    val deviceName: String = "",
    val counts: HomeCounts = HomeCounts(),
    val rules: List<RuleRow> = emptyList(),
    val recent: List<LogRowData> = emptyList(),
    /** True until proven otherwise (and until [loaded]) so the setup card does not flash on start. */
    val hasDestinations: Boolean = true,
    /** Some enabled rule has a destination ([RuleEngine.canForward]); true until [loaded]. */
    val canForward: Boolean = true,
    val loaded: Boolean = false,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val settingsFlow = combine(graph.settings.masterOn, graph.settings.deviceName) { on, name -> on to name }

    private data class RulesInfo(val rows: List<RuleRow>, val hasDestinations: Boolean, val canForward: Boolean)

    private val rulesFlow = combine(graph.rules.rules, graph.destinations.destinations) { rules, dests ->
        val labels = dests.associate { it.id to it.label }
        RulesInfo(
            rules.sortedWith(compareBy({ it.position }, { it.id }))
                .map { r -> RuleRow(r, r.destinationIds.mapNotNull { labels[it] }) },
            dests.isNotEmpty(),
            RuleEngine.canForward(rules, dests),
        )
    }

    private val recentFlow = graph.logRows(RECENT_LIMIT)

    /** Re-read on resume so "today" rolls over at midnight. */
    private val now = MutableStateFlow(System.currentTimeMillis())
    // Same rows and the same deliveryState as the Log chips; retention (48 h) bounds the read.
    private val countsFlow = now.flatMapLatest { n ->
        val from = startOfDay(n)
        graph.logs.recent(Int.MAX_VALUE).map { rows ->
            homeCounts(rows.filter { it.log.receivedAt >= from }.map { r ->
                deliveryState(r.log.skip?.let { SkipReason.fromCode(it) }, r.sends.map { it.state })
            })
        }
    }

    val state: StateFlow<HomeState> = combine(
        settingsFlow,
        countsFlow,
        rulesFlow,
        recentFlow,
    ) { (master, device), counts, info, recent ->
        HomeState(master, device, counts, info.rows, recent, info.hasDestinations, info.canForward, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), HomeState())

    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health

    private val healthChecker = HealthChecker(graph.appContext)

    /** Refreshes "today" and the health checks (off the main thread); call on resume. */
    fun onResume() {
        now.value = System.currentTimeMillis()
        viewModelScope.launch { _health.value = withContext(Dispatchers.Default) { healthChecker.items() } }
    }

    // Writes run on the app scope (a screen leaving mid-toggle cannot half-apply) and in order.
    private val writeLock = Mutex()

    fun setMaster(on: Boolean) = Forwarding.set(graph, on)

    fun setRuleEnabled(id: Long, on: Boolean) {
        graph.appScope.launch { writeLock.withLock { graph.rules.setEnabled(id, on) } }
    }

    companion object {
        const val RECENT_LIMIT = 3

        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(graph) as T
        }
    }
}
