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
import com.viswa2k.smsforwarder.core.WaMessage
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            val prefs = graph.whatsApp.filterNotNull().first()
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

    /** MessagingStyle messages (each with its person and time); else the big/plain text under the title. */
    private fun extract(n: Notification, postTime: Long): List<WaMessage> {
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val group = style.isGroupConversation
            val userKey = style.user.key
            return style.messages.mapNotNull { msg ->
                val person = msg.person ?: return@mapNotNull null // null person = the phone owner's own message
                if (userKey != null && person.key == userKey) return@mapNotNull null
                val text = msg.text?.toString() ?: return@mapNotNull null
                val name = person.name?.toString()
                val chat = if (group) style.conversationTitle?.toString() ?: title else name ?: title
                WaMessage(chat, if (group) name else null, text, msg.timestamp)
            }
        }
        val text = (n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras?.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return emptyList()
        val ts = n.`when`.takeIf { it > 0 } ?: postTime
        return listOf(WaMessage(title, null, text, ts))
    }

    private companion object { const val TAG = "WaListener" }
}
