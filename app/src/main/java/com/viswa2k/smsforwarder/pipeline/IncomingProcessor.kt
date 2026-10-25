package com.viswa2k.smsforwarder.pipeline

import android.util.Log
import androidx.room.withTransaction
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.classify.SenderIds
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.core.MessageCheck
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.MessageKey
import com.viswa2k.smsforwarder.core.NotificationText
import com.viswa2k.smsforwarder.core.Origin
import com.viswa2k.smsforwarder.core.Decision
import com.viswa2k.smsforwarder.core.RuleEngine
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.core.SourceFilter
import com.viswa2k.smsforwarder.data.db.LogEntity
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import com.viswa2k.smsforwarder.delivery.Dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Source { LIVE, CATCH_UP, DEBUG }

/**
 * The one path every message takes (SMS and WhatsApp notifications): classify, decide, then ONE transaction writes the log row (unique key =
 * dedupe) and its outbox rows. Never throws (a failure here must not crash the receiver); logs only the
 * exception class, never the SMS text or the bot token.
 *
 * [process] returns as soon as the rows are committed and the delivery job is scheduled. The inline send
 * runs on the app scope afterwards, so a receiver's `goAsync()` is never held across network calls.
 */
class IncomingProcessor(private val graph: AppGraph) {
    private val contacts by lazy { ContactLookup(graph.appContext) }

    /** [sentAtKnown] is false for inbox rows without `date_sent`: their key cannot match a live copy, so a looser check applies. */
    suspend fun process(sms: IncomingSms, source: Source, sentAtKnown: Boolean = true) {
        try {
            V1MigrationRunner.awaitDone(graph)
            val key = MessageKey.of(sms.sender, sms.sentAt, sms.body)
            // Cheap dedupe pre-check before contact lookup / classification; the insert stays the authority.
            if (graph.logs.exists(key)) return
            val loose = MessageKey.loose(sms.sender, sms.body)
            if (!sentAtKnown &&
                MessageKey.isLooseDuplicate(graph.logs.receivedAtByLooseKey(loose), sms.receivedAt)
            ) return

            val masterOn = graph.settings.masterOn.first()
            val kind = if (sms.origin == Origin.WHATSAPP) {
                contacts.kindForName(NotificationText.chatOf(sms.sender))
            } else {
                contacts.kind(sms.sender, SenderIds.parse(sms.sender))
            }
            val analysis = graph.analyzer.analyze(sms.sender, sms.body, kind, sms.origin)
            val waTypes = graph.settings.whatsAppTypes.first()
            val decision = if (SourceFilter.allows(sms.origin, analysis.type, waTypes)) {
                RuleEngine.decide(analysis.type, kind, sms.sender, masterOn, graph.rules.all(), graph.destinations.all())
            } else Decision(emptyList(), emptyList(), SkipReason.SOURCE_FILTERED)
            val device = graph.settings.deviceName.first()
            val now = System.currentTimeMillis()
            val texts = decision.sends.map {
                MessageFormatter.format(sms, analysis, it.style, it.template, it.destination.channel, device)
            }
            val entry = LogEntity(
                key = key, looseKey = loose,
                sender = sms.sender, body = sms.body, receivedAt = sms.receivedAt,
                type = analysis.type.code, senderKind = kind.code, extracted = MessageCheck.extractedLine(analysis),
                matchedRules = decision.matchedRuleIds.joinToString(","), skip = decision.skip?.code,
                simSlot = sms.subscriptionId, otpCode = analysis.otp?.code, origin = sms.origin.code,
            )
            val stored = graph.database.withTransaction {
                val id = graph.database.logs().insert(entry)
                if (id == -1L) return@withTransaction false // seen before: no outbox rows
                decision.sends.forEachIndexed { i, send ->
                    graph.outbox.insert(
                        OutboxEntity(
                            logId = id, destinationId = send.destination.id, channel = send.destination.channel.code,
                            text = texts[i], firstAttemptAt = now, nextAttemptAt = now,
                        ),
                    )
                }
                true
            }
            if (stored && decision.sends.isNotEmpty()) {
                // Schedule first: the rows survive even if this process dies before the inline send.
                Dispatcher.schedule(graph.appContext)
                graph.appScope.launch { deliverNow() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "processing failed (${source.name}): ${e.javaClass.simpleName}")
        }
    }

    private suspend fun deliverNow() {
        try {
            graph.dispatcher.sendDue(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "inline delivery failed: ${e.javaClass.simpleName}")
            runCatching { Dispatcher.schedule(graph.appContext) }
        }
    }

    private companion object { const val TAG = "IncomingProcessor" }
}
