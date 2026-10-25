package com.viswa2k.smsforwarder.pipeline

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.core.NotificationText
import com.viswa2k.smsforwarder.core.Origin
import com.viswa2k.smsforwarder.core.StyleMessage
import com.viswa2k.smsforwarder.core.WaMessage
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WhatsApp notification source (spec addendum §2). Event-driven: no polling. Every other package is
 * dropped before anything is read from its notification; nothing is read while the toggle is off. The
 * callback returns at once: parsing and [IncomingProcessor.process] run on the app scope. Never throws and
 * never logs notification text.
 */
class WhatsAppNotificationListener : NotificationListenerService() {
    private val graph by lazy { AppGraph.from(this) }
    private val processor by lazy { IncomingProcessor(graph) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !NotificationText.isAllowedPackage(sbn.packageName)) return
        try {
            // Fast path once the setting is cached; when not yet loaded the coroutine waits for it.
            if (graph.whatsApp.value?.enabled == false) return
            graph.appScope.launch { handle(sbn) }
        } catch (e: Exception) {
            Log.w(TAG, "notification skipped: ${e.javaClass.simpleName}")
        }
    }

    /** The system unbound us (e.g. after a crash or OEM kill): ask to be bound again. */
    override fun onListenerDisconnected() {
        try {
            requestRebind(ComponentName(this, WhatsAppNotificationListener::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "rebind failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        try {
            // The service may bind before ForwarderApp finished first-run migration.
            V1MigrationRunner.awaitDone(graph)
            val prefs = withTimeoutOrNull(PREFS_WAIT_MS) { graph.whatsApp.filterNotNull().first() } ?: return
            if (!prefs.enabled) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            if (n.category == Notification.CATEGORY_CALL) return
            val postTime = sbn.postTime
            val now = System.currentTimeMillis()
            for (m in extract(n, postTime)) {
                if (!NotificationText.accept(m, postTime, prefs.floor)) continue
                val sms = IncomingSms(
                    sender = NotificationText.senderAddress(m.chat, m.person), body = m.text.trim(),
                    sentAt = m.timestamp, receivedAt = now, subscriptionId = null, origin = Origin.WHATSAPP,
                )
                processor.process(sms, Source.LIVE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "notification failed: ${e.javaClass.simpleName}")
        }
    }

    /** Plain fields out of the notification; the selection rules live in [NotificationText.select]. */
    private fun extract(n: Notification, postTime: Long): List<WaMessage> {
        val extras = n.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        return NotificationText.select(
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            isGroup = style?.isGroupConversation == true,
            conversationTitle = style?.conversationTitle?.toString(),
            userKey = style?.user?.key,
            messages = style?.messages?.map {
                StyleMessage(it.text?.toString(), it.timestamp, it.person?.name?.toString(), it.person?.key, it.person != null)
            },
            fallbackText = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            fallbackTs = n.`when`.takeIf { it > 0 } ?: postTime,
        )
    }

    private companion object {
        const val TAG = "WaListener"
        const val PREFS_WAIT_MS = 10_000L
    }
}
