package com.viswa2k.smsforwarder.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.delivery.Dispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogState(
    val loaded: Boolean = false,
    val filter: LogFilter = LogFilter.ALL,
    val all: List<LogRowData> = emptyList(),
    val visible: List<LogRowData> = emptyList(),
)

/** Shared row mapping: labels and rule names are looked up by id. Used by Home and Log. */
fun AppGraph.logRows(limit: Int) = combine(logs.recent(limit), destinations.destinations, rules.rules) { logs, dests, rules ->
    val labels = dests.associate { it.id to it.label }
    val names = rules.associate { it.id to it.name }
    logs.map { it.toRowData(labels, names) }
}

class LogViewModel(private val graph: AppGraph) : ViewModel() {
    private val filter = MutableStateFlow(LogFilter.ALL)

    val state: StateFlow<LogState> = combine(graph.logRows(LIMIT), filter) { rows, f ->
        LogState(loaded = true, filter = f, all = rows, visible = rows.filter { f.matches(it.state) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogState())

    fun setFilter(f: LogFilter) { filter.value = f }

    // One-shot per tap: the ids of resends in flight (tap on main, release on the app scope).
    private val resending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Re-queues a FAILED send and kicks the delivery job. Runs on the app scope so leaving the screen cannot half-apply it. */
    fun resend(outboxId: Long) {
        if (!resending.add(outboxId)) return
        val context = graph.appContext
        graph.appScope.launch {
            try {
                graph.outbox.resend(outboxId)
                Dispatcher.schedule(context)
            } finally {
                resending.remove(outboxId)
            }
        }
    }

    fun clear() {
        graph.appScope.launch { graph.logs.clear() }
    }

    companion object {
        const val LIMIT = 200

        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(graph) as T
        }
    }
}
