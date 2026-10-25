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
import androidx.work.WorkInfo
import com.viswa2k.smsforwarder.core.DeliveryOrder
import com.viswa2k.smsforwarder.core.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Sends every due outbox row (oldest first) and applies [RetryPolicy]. One pass at a time (the
 * mutex keeps the live pipeline and the worker from sending the same row twice).
 */
class Dispatcher(private val graph: AppGraph) {
    private val lock = Mutex()

    /**
     * One pass. [fromWorker] is true when [DeliveryWorker] runs it: only then may the job be replaced
     * at the end (a running worker is never replaced from outside, which would cancel it mid-send).
     */
    suspend fun sendDue(now: Long, fromWorker: Boolean = false) {
        lock.withLock {
            val pending = graph.outbox.pending()
            if (pending.isNotEmpty()) {
                val byId = pending.associateBy { it.id }
                val destinations = graph.destinations.all().associateBy { it.id }
                val order = DeliveryOrder(
                    pending.map { DeliveryOrder.Row(it.id, it.destinationId, it.firstAttemptAt, it.nextAttemptAt) }, now,
                )
                while (true) {
                    val next = order.next() ?: break
                    val row = byId.getValue(next.id)
                    // A send that was started is always recorded, even if the caller is cancelled.
                    val outcome = withContext(NonCancellable) { deliver(row, destinations[row.destinationId], now) }
                    if (outcome.retryAt != null) order.onRetry(next, outcome.retryAt) else if (outcome.sent) order.onSent(next)
                }
                for ((destinationId, nextAt) in order.deferrals()) graph.outbox.deferPending(destinationId, nextAt)
            }
        }
        schedule(graph.appContext, replaceRunning = fromWorker)
    }

    private class Outcome(val sent: Boolean, val retryAt: Long?)

    private suspend fun deliver(row: OutboxEntity, destination: Destination?, now: Long): Outcome {
        if (destination == null) {
            graph.outbox.markFailed(row.id, "destination removed")
            return Outcome(sent = false, retryAt = null)
        }
        val result = try {
            val original = graph.logs.bodyOf(row.logId).orEmpty()
            when (destination.channel) {
                Channel.TELEGRAM ->
                    graph.telegram.send(destination.address, destination.topicId, prepareTelegramText(row.text, original))
                Channel.SMS ->
                    graph.smsSender.send(destination.address, prepareSmsText(row.text, original), destination.simSubscriptionId)
            }
        } catch (e: Exception) {
            SendResult.Retry("Unexpected error: ${e.javaClass.simpleName}")
        }
        return when (result) {
            SendResult.Sent -> {
                graph.outbox.markSent(row.id)
                Outcome(sent = true, retryAt = null)
            }
            is SendResult.Fatal -> {
                graph.outbox.markFailed(row.id, result.reason)
                Outcome(sent = false, retryAt = null)
            }
            is SendResult.Retry -> {
                val next = RetryPolicy.nextAttemptAt(row.attempts, row.firstAttemptAt, now, result.retryAfterMs)
                if (next == null) {
                    graph.outbox.markFailed(row.id, "Gave up after 24 h: ${result.reason}")
                    Outcome(sent = false, retryAt = null)
                } else {
                    graph.outbox.markRetry(row.id, next, result.reason)
                    Outcome(sent = false, retryAt = next)
                }
            }
        }
    }

    companion object {
        const val WORK_NAME = "delivery"

        /**
         * (Re)enqueues the unique delivery job to run at the earliest pending row's due time, once the
         * network is available. REPLACE keeps the delay tracking the earliest row, but a RUNNING job is
         * left alone unless [replaceRunning] (the worker re-schedules itself when its pass ends).
         * No-op without pending rows.
         */
        suspend fun schedule(context: Context, replaceRunning: Boolean = false) {
            val graph = AppGraph.from(context)
            val earliest = graph.outbox.earliestPendingAt() ?: return
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!replaceRunning) {
                val infos = withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(WORK_NAME).get() }
                if (infos.any { it.state == WorkInfo.State.RUNNING }) return
            }
            val delay = (earliest - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
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
