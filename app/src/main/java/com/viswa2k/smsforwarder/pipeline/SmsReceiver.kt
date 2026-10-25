package com.viswa2k.smsforwarder.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.IncomingSmsAssembler
import com.viswa2k.smsforwarder.core.Pdu
import kotlinx.coroutines.launch

/** Manifest-registered SMS_RECEIVED receiver: the process is started by the OS even after a swipe or reboot. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        try {
            val subscription = intent.getIntExtra(
                "android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1),
            ).takeIf { it >= 0 }
            val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().map {
                Pdu(it.originatingAddress.orEmpty(), it.messageBody.orEmpty(), it.timestampMillis, subscription)
            }
            val messages = IncomingSmsAssembler.assemble(pdus, System.currentTimeMillis())
            val graph = AppGraph.from(context)
            val processor = IncomingProcessor(graph)
            graph.appScope.launch {
                try {
                    for (sms in messages) processor.process(sms, Source.LIVE)
                } finally {
                    pending.finish()
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "receive failed: ${e.javaClass.simpleName}")
            pending.finish()
        }
    }
}
