package com.viswa2k.smsforwarder.cloud.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.viswa2k.smsforwarder.MainActivity
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.dataStore
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Option 1 push: while the foreground service is alive, listen to this device's cloud inbox
 * and post a LOCAL notification for each newly-arrived message. No FCM / Cloud Functions /
 * paid plan required — it just rides on the realtime Firestore listener.
 *
 * Behaviour & limits (by design):
 * - Only runs when signed in AND "Receive cloud messages" is on AND the device is registered.
 * - The first snapshot is treated as a baseline (existing messages are NOT re-notified).
 * - Delivers notifications for messages that arrive WHILE the listener is attached. Messages
 *   that land while the service is dead are surfaced the next time you open Cloud SMS, not as
 *   a background notification — that gap is exactly what the FCM-based approach (Option 2) closes.
 */
class CloudInboxNotifier(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val prefs = UserPreferences(appContext.dataStore)
    private val crypto = CryptoManager(appContext)
    private val deviceRepo = DeviceRepository(context = appContext, crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)

    private var registration: ListenerRegistration? = null
    private var seenBaseline = false
    private val notified = HashSet<String>()

    fun start() {
        if (registration != null) return
        scope.launch {
            if (!prefs.isReceiveEnabled.first()) return@launch
            if (FirebaseProvider.auth.currentUser == null) return@launch
            val deviceId = prefs.cloudDeviceId.first()
            if (deviceId.isBlank()) return@launch
            ensureChannel()
            registration = FirebaseProvider.db.collection("inbox").document(deviceId)
                .collection("messages").addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    // First callback = baseline of already-stored messages; don't notify those.
                    if (!seenBaseline) {
                        snap.documents.forEach { notified.add(it.id) }
                        seenBaseline = true
                        return@addSnapshotListener
                    }
                    for (change in snap.documentChanges) {
                        if (change.type != DocumentChange.Type.ADDED) continue
                        val doc = change.document
                        if (!notified.add(doc.id)) continue // already handled
                        val messageId = doc.getString("messageId") ?: doc.id
                        scope.launch {
                            runCatching {
                                val msg = messageRepo.decryptOne(deviceId, messageId, emptyMap()) ?: return@runCatching
                                postNotification(msg)
                            }.onFailure { Log.w(TAG, "notify failed", it) }
                        }
                    }
                }
        }
    }

    fun stop() {
        registration?.remove()
        registration = null
        seenBaseline = false
        notified.clear()
    }

    private fun postNotification(msg: CloudMessageRepository.DecryptedMessage) {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            appContext, msg.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Cloud SMS · ${msg.sourceAlias}"
        val text = if (msg.sender.isNotBlank()) "${msg.sender}: ${msg.body}" else msg.body
        val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(msg.id.hashCode(), n)
        }.onFailure { Log.w(TAG, "post failed (notifications disabled?)", it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Cloud SMS messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a new message arrives in your cloud inbox"
            }
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "CloudInboxNotifier"
        private const val CHANNEL_ID = "cloud_sms_messages"
    }
}
