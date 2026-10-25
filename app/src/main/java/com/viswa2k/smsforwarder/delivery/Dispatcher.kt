package com.viswa2k.smsforwarder.delivery

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.RetryPolicy
import com.viswa2k.smsforwarder.core.SendResult
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Sends every due outbox row (oldest first) and applies [RetryPolicy]. One pass at a time (the
 * mutex keeps the live pipeline and the worker from sending the same row twice).
 */
class Dispatcher(private val graph: AppGraph) {
    private val lock = Mutex()

    suspend fun sendDue(now: Long) {
        lock.withLock {
            val rows = graph.outbox.due(now)
            if (rows.isNotEmpty()) {
                val destinations = graph.destinations.all().associateBy { it.id }
                for (row in rows) deliver(row, destinations[row.destinationId])
            }
        }
        schedule(graph.appContext)
    }

    private suspend fun deliver(row: OutboxEntity, destination: com.viswa2k.smsforwarder.core.Destination?) {
        if (destination == null) {
            graph.outbox.markFailed(row.id, "destination removed")
            return
        }
        val result = try {
            val original = graph.logs.bodyOf(row.logId).orEmpty()
            when (destination.channel) {
                Channel.TELEGRAM ->
                    graph.telegram.send(destination.address, destination.topicId, prepareTelegramText(row.text, original))
                Channel.SMS ->
                    graph.smsSender.send(destination.address, prepareSmsText(row.text, original), destination.simSubscriptionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Retry("Unexpected error: ${e.javaClass.simpleName}")
        }
        when (result) {
            SendResult.Sent -> graph.outbox.markSent(row.id)
            is SendResult.Fatal -> graph.outbox.markFailed(row.id, result.reason)
            is SendResult.Retry -> {
                val now = System.currentTimeMillis()
                val next = RetryPolicy.nextAttemptAt(row.attempts, row.firstAttemptAt, now, result.retryAfterMs)
                if (next == null) graph.outbox.markFailed(row.id, "Gave up after 24 h: ${result.reason}")
                else graph.outbox.markRetry(row.id, next, result.reason)
            }
        }
    }

    companion object {
        const val WORK_NAME = "delivery"

        /**
         * (Re)enqueues the unique delivery job to run at the earliest pending row's due time, once the
         * network is available. REPLACE keeps the delay tracking the earliest row. No-op without pending rows.
         */
        suspend fun schedule(context: Context) {
            val graph = AppGraph.from(context)
            val earliest = graph.outbox.earliestPendingAt() ?: return
            val delay = (earliest - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Ruling 10: blank text falls back to the escaped original SMS; the result never exceeds Telegram's limit. */
internal fun prepareTelegramText(text: String, originalBody: String): String {
    val base = text.ifBlank { MessageFormatter.escapeHtml(originalBody) }
    if (base.length <= MessageFormatter.TELEGRAM_MAX) return base
    // Over the limit (the formatter normally prevents this): plain escaped body, so no tag can be cut open.
    val escaped = MessageFormatter.escapeHtml(originalBody.ifBlank { base })
    var end = (MessageFormatter.TELEGRAM_MAX - 1).coerceAtMost(escaped.length)
    if (escaped.length > end) {
        val amp = escaped.lastIndexOf('&', end - 1)
        if (amp >= 0 && amp >= end - 5 && escaped.indexOf(';', amp).let { it < 0 || it >= end }) end = amp
        if (end > 0 && Character.isHighSurrogate(escaped[end - 1])) end--
        return escaped.substring(0, end) + "…"
    }
    return escaped
}

internal fun prepareSmsText(text: String, originalBody: String): String = text.ifBlank { originalBody }
