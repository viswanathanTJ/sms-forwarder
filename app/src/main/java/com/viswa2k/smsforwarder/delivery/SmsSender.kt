package com.viswa2k.smsforwarder.delivery

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.core.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends a text through [SmsManager] and waits for the radio's "sent" result of every part. A one-shot
 * receiver with a per-send action is registered for the call and always unregistered.
 */
class SmsSender(context: Context) {
    private val context = context.applicationContext

    suspend fun send(number: String, text: String, subscriptionId: Int?): SendResult {
        val manager = try {
            manager(subscriptionId)
        } catch (e: RuntimeException) {
            return SendResult.Fatal("SMS unavailable: ${e.javaClass.simpleName}")
        }
        val parts = try {
            manager.divideMessage(text)
        } catch (e: RuntimeException) {
            return SendResult.Fatal("Cannot split message: ${e.javaClass.simpleName}")
        }
        if (parts.isEmpty()) return SendResult.Fatal("Empty message")

        val sendId = counter.incrementAndGet()
        val action = "$ACTION_PREFIX$sendId"
        val outcome = CompletableDeferred<Int>()
        val pending = AtomicInteger(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val code = resultCode
                if (code != Activity.RESULT_OK) outcome.complete(code)
                else if (pending.decrementAndGet() == 0) outcome.complete(Activity.RESULT_OK)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(action).setPackage(context.packageName)
                sentIntents += PendingIntent.getBroadcast(context, sendId * 1000 + i, intent, PendingIntent.FLAG_IMMUTABLE)
            }
            try {
                manager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            } catch (e: RuntimeException) { // SecurityException (no SEND_SMS), IllegalArgumentException (bad number)
                return SendResult.Fatal("SMS rejected: ${e.javaClass.simpleName}")
            }
            val code = withTimeoutOrNull(TIMEOUT_MS) { outcome.await() }
                ?: return SendResult.Retry("No SMS sent confirmation within 60 s")
            return map(code)
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        }
    }

    private fun map(code: Int): SendResult = when (code) {
        Activity.RESULT_OK -> SendResult.Sent
        SmsManager.RESULT_ERROR_RADIO_OFF -> SendResult.Retry("Radio off")
        SmsManager.RESULT_ERROR_NO_SERVICE -> SendResult.Retry("No mobile service")
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SendResult.Fatal("SMS failed (generic failure)")
        SmsManager.RESULT_ERROR_NULL_PDU -> SendResult.Fatal("SMS failed (null PDU)")
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SendResult.Fatal("SMS limit exceeded")
        else -> SendResult.Fatal("SMS failed (code $code)")
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int?): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null) base.createForSubscriptionId(subscriptionId) else base
        } else if (subscriptionId != null) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getDefault()
        }

    private companion object {
        const val ACTION_PREFIX = "com.viswa2k.smsforwarder.SMS_SENT."
        const val TIMEOUT_MS = 60_000L
        val counter = AtomicInteger(0)
    }
}
