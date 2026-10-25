package com.viswa2k.smsforwarder.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.core.NotificationText
import com.viswa2k.smsforwarder.core.Origin
import com.viswa2k.smsforwarder.pipeline.IncomingProcessor
import com.viswa2k.smsforwarder.pipeline.Source
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook (the emulator cannot inject real SMS):
 * `adb shell am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body "text"`
 * Add `--es origin whatsapp` to feed a WhatsApp message (`from` = chat title) past the listener's filters.
 */
class InjectSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val now = System.currentTimeMillis()
        val wa = intent.getStringExtra("origin").equals("whatsapp", ignoreCase = true)
        val from = intent.getStringExtra("from") ?: "TEST"
        val sms = IncomingSms(
            sender = if (wa) NotificationText.senderAddress(from, null) else from,
            body = intent.getStringExtra("body").orEmpty(),
            sentAt = now, receivedAt = now, subscriptionId = null,
            origin = if (wa) Origin.WHATSAPP else Origin.SMS,
        )
        val graph = AppGraph.from(context)
        graph.appScope.launch {
            try {
                IncomingProcessor(graph).process(sms, Source.DEBUG)
            } finally {
                pending.finish()
            }
        }
    }
}
