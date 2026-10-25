package com.viswa2k.smsforwarder.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.IncomingSms
import com.viswa2k.smsforwarder.pipeline.IncomingProcessor
import com.viswa2k.smsforwarder.pipeline.Source
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook (the emulator cannot inject real SMS):
 * `adb shell am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body "text"`
 */
class InjectSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val now = System.currentTimeMillis()
        val sms = IncomingSms(
            sender = intent.getStringExtra("from") ?: "TEST",
            body = intent.getStringExtra("body").orEmpty(),
            sentAt = now, receivedAt = now, subscriptionId = null,
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
