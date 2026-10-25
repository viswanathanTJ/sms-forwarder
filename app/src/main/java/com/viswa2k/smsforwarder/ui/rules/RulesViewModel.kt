package com.viswa2k.smsforwarder.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.ui.home.RuleRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** `null` until the first emission, so the empty message does not flash. */
class RulesViewModel(private val graph: AppGraph) : ViewModel() {
    val rules: StateFlow<List<RuleRow>?> = combine(graph.rules.rules, graph.destinations.destinations) { rules, dests ->
        val labels = dests.associate { it.id to it.label }
        rules.sortedWith(compareBy({ it.position }, { it.id }))
            .map { r -> RuleRow(r, r.destinationIds.mapNotNull { labels[it] }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val writeLock = Mutex()

    fun setEnabled(id: Long, on: Boolean) {
        graph.appScope.launch { writeLock.withLock { graph.rules.setEnabled(id, on) } }
    }

    /** [delta] -1 = up, +1 = down; the DAO clamps and renumbers. */
    fun move(id: Long, delta: Int) {
        val index = rules.value?.indexOfFirst { it.rule.id == id } ?: return
        if (index < 0) return
        graph.appScope.launch { writeLock.withLock { graph.rules.move(id, index + delta) } }
    }

    companion object {
        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RulesViewModel(graph) as T
        }
    }
}
