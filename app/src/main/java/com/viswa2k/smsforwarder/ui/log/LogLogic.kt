package com.viswa2k.smsforwarder.ui.log

import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.data.db.LogWithSends
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.ui.common.DeliveryState

/** Pure Log/Home row logic (no Android types), shared by both screens. */

/** Same semantics as the Home counts: skip or no sends -> Skipped; any pending -> Retrying; any failed -> Failed. */
fun deliveryState(skip: SkipReason?, sendStates: List<Int>): DeliveryState = when {
    skip != null || sendStates.isEmpty() -> DeliveryState.SKIPPED
    OutboxEntity.STATE_PENDING in sendStates -> DeliveryState.RETRYING
    OutboxEntity.STATE_FAILED in sendStates -> DeliveryState.FAILED
    else -> DeliveryState.FORWARDED
}

enum class LogFilter {
    ALL, FORWARDED, SKIPPED, FAILED_RETRYING;

    fun matches(state: DeliveryState): Boolean = when (this) {
        ALL -> true
        FORWARDED -> state == DeliveryState.FORWARDED
        SKIPPED -> state == DeliveryState.SKIPPED
        FAILED_RETRYING -> state == DeliveryState.RETRYING || state == DeliveryState.FAILED
    }
}

/** The grey line under a row. [Skipped] and [MatchedNothingSent] text comes from string resources. */
sealed interface NoteLine {
    data class Skipped(val reason: SkipReason) : NoteLine
    data class Routed(val rules: List<String>, val destinations: List<String>) : NoteLine {
        /** "OTPs → Me, Family group" (just "→ Me" when the rule names are gone). */
        fun text(): String {
            val to = "→ " + destinations.joinToString(", ")
            return if (rules.isEmpty()) to else rules.joinToString(", ") + " " + to
        }
    }
    data object MatchedNothingSent : NoteLine
}

fun noteLine(skip: SkipReason?, ruleNames: List<String>, destinations: List<String>): NoteLine = when {
    skip != null -> NoteLine.Skipped(skip)
    destinations.isNotEmpty() -> NoteLine.Routed(ruleNames, destinations)
    else -> NoteLine.MatchedNothingSent
}

data class SendRow(val outboxId: Long, val destination: String?, val state: Int, val error: String?)

data class LogRowData(
    val id: Long,
    val sender: String,
    /** As stored: already masked when [masked]. */
    val body: String,
    val receivedAt: Long,
    val type: MessageType?,
    val state: DeliveryState,
    val skip: SkipReason?,
    val extracted: String,
    val masked: Boolean,
    val ruleNames: List<String>,
    val destinationLabels: List<String>,
    val sends: List<SendRow>,
) {
    val note: NoteLine get() = noteLine(skip, ruleNames, destinationLabels)
}

fun LogWithSends.toRowData(destinationLabels: Map<Long, String>, ruleNames: Map<Long, String>): LogRowData {
    val skipReason = log.skip?.let { SkipReason.fromCode(it) }
    return LogRowData(
        id = log.id,
        sender = log.sender,
        body = log.body,
        receivedAt = log.receivedAt,
        type = MessageType.fromCode(log.type),
        state = deliveryState(skipReason, sends.map { it.state }),
        skip = skipReason,
        extracted = log.extracted,
        masked = log.masked,
        ruleNames = log.matchedRules.split(',').mapNotNull { it.trim().toLongOrNull() }.mapNotNull { ruleNames[it] },
        destinationLabels = sends.mapNotNull { destinationLabels[it.destinationId] }.distinct(),
        sends = sends.map { SendRow(it.id, destinationLabels[it.destinationId], it.state, it.lastError) },
    )
}
