package com.viswa2k.smsforwarder.pipeline

import android.util.Log
import androidx.room.withTransaction
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.classify.SenderIds
import com.viswa2k.smsforwarder.core.Analysis
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.MessageKey
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.RuleEngine
import com.viswa2k.smsforwarder.data.db.LogEntity
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.delivery.Dispatcher
import com.viswa2k.smsforwarder.finance.TxnDirection
import kotlinx.coroutines.flow.first
import java.util.Locale

enum class Source { LIVE, CATCH_UP, DEBUG }

/**
 * The one path every SMS takes: dedupe, classify, decide, queue, send. Never throws (a failure here must
 * not crash the receiver); logs only the exception class, never the SMS text or the bot token.
 */
class IncomingProcessor(private val graph: AppGraph) {
    private val contacts by lazy { ContactLookup(graph.appContext) }

    suspend fun process(sms: IncomingSms, source: Source) {
        var logId: Long? = null
        var decided = false
        try {
            val key = MessageKey.of(sms.sender, sms.sentAt, sms.body)
            // Placeholder row claims the key; the real decision lands in the same transaction as the outbox rows.
            logId = graph.logs.insertIfNew(
                LogEntity(
                    key = key, sender = sms.sender, body = sms.body, receivedAt = sms.receivedAt,
                    type = 0, senderKind = 0, extracted = "", matchedRules = "", skip = null,
                    simSlot = sms.subscriptionId,
                ),
            ) ?: return

            val masterOn = graph.settings.masterOn.first()
            val kind = contacts.kind(sms.sender, SenderIds.parse(sms.sender))
            val analysis = graph.analyzer.analyze(sms.sender, sms.body, kind)
            val decision = RuleEngine.decide(
                analysis.type, kind, sms.sender, masterOn, graph.rules.all(), graph.destinations.all(),
            )
            val device = graph.settings.deviceName.first()
            val now = System.currentTimeMillis()
            val texts = decision.sends.map {
                MessageFormatter.format(sms, analysis, it.style, it.template, it.destination.channel, device)
            }
            val id = logId
            graph.database.withTransaction {
                graph.database.logs().updateDecision(
                    id = id, type = analysis.type.code, senderKind = kind.code,
                    extracted = extractedLine(analysis), matchedRules = decision.matchedRuleIds.joinToString(","),
                    skip = decision.skip?.code, otpCode = analysis.otp?.code,
                )
                decision.sends.forEachIndexed { i, send ->
                    graph.outbox.insert(
                        OutboxEntity(
                            logId = id, destinationId = send.destination.id, channel = send.destination.channel.code,
                            text = texts[i], firstAttemptAt = now, nextAttemptAt = now,
                        ),
                    )
                }
            }
            decided = true
            if (decision.sends.isNotEmpty()) deliverNow()
        } catch (e: Exception) {
            Log.e(TAG, "processing failed (${source.name}): ${e.javaClass.simpleName}")
            // Undecided placeholder: free the key so the catch-up scan can retry the message.
            if (!decided) logId?.let { runCatching { graph.logs.discard(it) } }
        }
    }

    private suspend fun deliverNow() {
        try {
            graph.dispatcher.sendDue(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "inline delivery failed: ${e.javaClass.simpleName}")
            runCatching { Dispatcher.schedule(graph.appContext) }
        }
    }

    internal fun extractedLine(a: Analysis): String = when {
        a.otp != null -> "Code ${a.otp.code}"
        a.type == MessageType.TRANSACTION && a.txn != null && a.txn.amount > 0 -> {
            val amount = String.format(Locale.US, "%,.2f", a.txn.amount).removeSuffix(".00")
            val dir = when (a.txn.direction) {
                TxnDirection.DEBIT -> " debit"
                TxnDirection.CREDIT -> " credit"
                else -> ""
            }
            "₹$amount$dir"
        }
        a.missedCall != null -> a.missedCall.caller?.let { "From $it" } ?: ""
        else -> ""
    }

    private companion object { const val TAG = "IncomingProcessor" }
}
