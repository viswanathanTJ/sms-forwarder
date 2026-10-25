package com.viswa2k.smsforwarder.pipeline

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.CatchUpWindow
import com.viswa2k.smsforwarder.core.IncomingSms
import kotlinx.coroutines.flow.first

/**
 * Gap filler (spec 5.8): feeds inbox messages the live receiver missed through [IncomingProcessor]. Dedupe
 * keys make already-forwarded messages no-ops.
 */
class InboxCatchUp(private val graph: AppGraph, private val processor: IncomingProcessor = IncomingProcessor(graph)) {
    suspend fun run(now: Long) {
        val plan = CatchUpWindow.plan(now, graph.settings.catchUpFloor.first())
        if (plan !is CatchUpWindow.Plan.Scan) {
            // First run: never forward the old inbox.
            graph.settings.setCatchUpFloor(now)
            return
        }
        val granted = ContextCompat.checkSelfPermission(graph.appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val since = plan.since
        val found = ArrayList<Pair<IncomingSms, Boolean>>()
        try {
            graph.appContext.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE_SENT,
                    Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID,
                ),
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val date = c.getLong(3)
                    val dateSent = c.getLong(2)
                    val sub = if (c.isNull(4)) null else c.getInt(4).takeIf { it >= 0 }
                    found += IncomingSms(
                        sender = c.getString(0).orEmpty(),
                        body = c.getString(1).orEmpty(),
                        // Same clock as SmsMessage.timestampMillis, so the keys match live PDUs.
                        sentAt = dateSent.takeIf { it > 0 } ?: date,
                        receivedAt = date,
                        subscriptionId = sub,
                    ) to (dateSent > 0)
                }
            }
        } catch (e: Exception) {
            Log.e("InboxCatchUp", "inbox query failed: ${e.javaClass.simpleName}")
            return
        }
        for ((sms, sentAtKnown) in found) processor.process(sms, Source.CATCH_UP, sentAtKnown)
    }
}
