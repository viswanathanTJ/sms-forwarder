package com.viswa2k.smsforwarder.ui.rules

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.MessageAnalyzer
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.core.SenderKind
import com.viswa2k.smsforwarder.core.Style
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Lets an action run once: the first [tryRun] returns true, every later one false (double-tap guard). */
class OneShot {
    private val fired = java.util.concurrent.atomic.AtomicBoolean(false)
    val isFired: Boolean get() = fired.get()
    fun tryRun(): Boolean = fired.compareAndSet(false, true)
}

/** Editable copy of a [Rule]; every change returns a new state (pure, unit-tested). */
data class RuleEditorState(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val allMessages: Boolean = false,
    val types: Set<MessageType> = emptySet(),
    val sender: SenderCondition = SenderCondition.ANYONE,
    val destinationIds: Set<Long> = emptySet(),
    val style: Style = Style.SMART,
    val customTemplate: String = DEFAULT_TEMPLATE,
    val position: Int = 0,
) {
    val canSave: Boolean get() = name.isNotBlank() && (allMessages || types.isNotEmpty()) && destinationIds.isNotEmpty()

    /** SPAM is never selectable; selecting a type clears "All messages". */
    fun toggleType(t: MessageType): RuleEditorState {
        if (t == MessageType.SPAM) return this
        return copy(allMessages = false, types = if (t in types) types - t else types + t)
    }

    /** Selecting "All messages" clears the types; selecting it again turns it off. */
    fun toggleAll(): RuleEditorState = copy(allMessages = !allMessages, types = emptySet())

    /** Drops destination ids that no longer exist. */
    fun retainDestinations(existing: Set<Long>): RuleEditorState =
        if (destinationIds.all { it in existing }) this else copy(destinationIds = destinationIds intersect existing)

    fun toggleDestination(id: Long): RuleEditorState =
        copy(destinationIds = if (id in destinationIds) destinationIds - id else destinationIds + id)

    fun toRule(): Rule = Rule(
        id = id,
        name = name.trim(),
        enabled = enabled,
        allMessages = allMessages,
        types = if (allMessages) emptySet() else types,
        sender = sender,
        destinationIds = destinationIds.sorted(),
        style = style,
        customTemplate = customTemplate.takeIf { style == Style.CUSTOM },
        position = position,
    )

    /**
     * Up to two sample messages (OTP, Transaction, Missed call; the Transaction one when nothing
     * else applies) run through the real [analyzer] and [MessageFormatter] for Telegram.
     */
    fun preview(analyzer: MessageAnalyzer, deviceName: String, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val picked = SAMPLES.filter { !allMessages && it.type in types }.ifEmpty { SAMPLES.filter { it.type == MessageType.TRANSACTION } }
        return picked.take(2).map { s ->
            val sms = IncomingSms(s.sender, s.body, SAMPLE_TIME, SAMPLE_TIME, null)
            val analysis = analyzer.analyze(s.sender, s.body, SenderKind.BUSINESS)
            MessageFormatter.format(sms, analysis, style, customTemplate.takeIf { style == Style.CUSTOM }, Channel.TELEGRAM, deviceName, zone)
        }
    }

    private class Sample(val type: MessageType, val sender: String, val body: String)

    companion object {
        /** Literal `\n` is the newline token (spec 5.5). */
        const val DEFAULT_TEMPLATE = "%r · %s\\n%m"
        private const val SAMPLE_TIME = 1_792_922_460_000L // 2026-10-25 10:41 UTC-ish; only used for call time fallback

        private val SAMPLES = listOf(
            Sample(MessageType.OTP, "AD-AMAZON", "482913 is your Amazon OTP. Do not share it with anyone."),
            Sample(
                MessageType.TRANSACTION, "VM-HDFCBK",
                "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank",
            ),
            Sample(MessageType.MISSED_CALL, "JM-AIRTEL", "You have a missed call from +91 99620 55555 at 10:11 on 25-Oct. -Airtel"),
        )

        fun from(rule: Rule) = RuleEditorState(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            allMessages = rule.allMessages,
            types = rule.types,
            sender = rule.sender,
            destinationIds = rule.destinationIds.toSet(),
            style = rule.style,
            customTemplate = rule.customTemplate ?: DEFAULT_TEMPLATE,
            position = rule.position,
        )
    }
}

class RuleEditorViewModel(private val graph: AppGraph, private val ruleId: Long) : ViewModel() {
    private val _state = MutableStateFlow<RuleEditorState?>(null)
    val state: StateFlow<RuleEditorState?> = _state

    /** True when an existing rule id was not found (deleted meanwhile): the screen closes. */
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing

    val destinations: StateFlow<List<Destination>?> =
        graph.destinations.destinations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Save or delete already happened (or is in flight): the screen disables its buttons and closes once. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished
    private val once = OneShot()

    private data class PreviewInput(
        val allMessages: Boolean,
        val types: Set<MessageType>,
        val style: Style,
        val template: String?,
        val device: String,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val preview: StateFlow<List<String>> = combine(_state.filterNotNull(), graph.settings.deviceName) { s, name ->
        PreviewInput(s.allMessages, s.types, s.style, s.customTemplate.takeIf { s.style == Style.CUSTOM }, name.ifBlank { Build.MODEL })
    }.distinctUntilChanged().mapLatest { i ->
        try {
            val sample = RuleEditorState(allMessages = i.allMessages, types = i.types, style = i.style, customTemplate = i.template ?: RuleEditorState.DEFAULT_TEMPLATE)
            withContext(Dispatchers.Default) { sample.preview(graph.analyzer, i.device) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            if (ruleId == 0L) {
                // New rule: pre-select the first destination, as in the mock.
                val first = graph.destinations.all().firstOrNull()
                _state.value = RuleEditorState(destinationIds = setOfNotNull(first?.id))
            } else {
                val rule = graph.rules.all().firstOrNull { it.id == ruleId }
                if (rule == null) _missing.value = true
                else _state.value = RuleEditorState.from(rule).retainDestinations(graph.destinations.all().map { it.id }.toSet())
            }
        }
    }

    fun update(change: (RuleEditorState) -> RuleEditorState) = _state.update { it?.let(change) }

    // Writes run on the app scope (leaving the screen cannot cancel them) and in order.
    /** True when the save was started (once only); the caller then closes the screen. */
    fun save(): Boolean {
        val ids = destinations.value?.map { it.id }?.toSet() ?: return false
        val rule = _state.value?.retainDestinations(ids)?.takeIf { it.canSave }?.toRule() ?: return false
        if (!once.tryRun()) return false
        _finished.value = true
        graph.appScope.launch { writeLock.withLock { graph.rules.save(rule) } }
        return true
    }

    /** True when the delete was started (once only); the caller then closes the screen. */
    fun delete(): Boolean {
        if (ruleId == 0L || !once.tryRun()) return false
        _finished.value = true
        graph.appScope.launch { writeLock.withLock { graph.rules.delete(ruleId) } }
        return true
    }

    companion object {
        private val writeLock = Mutex()

        fun factory(graph: AppGraph, ruleId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RuleEditorViewModel(graph, ruleId) as T
        }
    }
}
