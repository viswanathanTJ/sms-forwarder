package com.viswa2k.smsforwarder.cloud.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.cloud.data.AccessRepository
import com.viswa2k.smsforwarder.cloud.data.CloudMessageRepository
import com.viswa2k.smsforwarder.cloud.data.DeviceRepository
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmsForwarderFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val prefs = UserPreferences(applicationContext.dataStore)
        val deviceRepo = DeviceRepository(crypto = CryptoManager(applicationContext), prefs = prefs)
        CoroutineScope(Dispatchers.IO).launch { runCatching { deviceRepo.updateFcmToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "new_sms") return
        val messageId = data["message_id"] ?: return
        val ctx = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = UserPreferences(ctx.dataStore)
            if (!prefs.isReceiveEnabled.first()) return@launch
            val readerDeviceId = prefs.cloudDeviceId.first()
            if (readerDeviceId.isBlank()) return@launch
            val crypto = CryptoManager(ctx)
            val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
            val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = AccessRepository())
            val aliases = runCatching { deviceRepo.fetchFleetDevices().associate { it.id to it.alias } }.getOrDefault(emptyMap())
            val decrypted = runCatching { messageRepo.decryptOne(readerDeviceId, messageId, aliases) }.getOrNull() ?: return@launch
            showNotification(ctx, decrypted.sourceAlias, "${decrypted.sender}: ${decrypted.body}")
        }
    }

    private fun showNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Cloud SMS", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(body.hashCode(), n)
        }
    }

    companion object { private const val CHANNEL_ID = "cloud_sms" }
}
