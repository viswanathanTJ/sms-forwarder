package com.viswa2k.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log

class SmsForwarderService : Service() {

    private lateinit var smsReceiver: SmsReceiver
    private var isReceiverRegistered = false // Track receiver registration status

    override fun onCreate() {
        super.onCreate()
        Log.d("SmsForwarderService", "Service created.")
        smsReceiver = SmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create a notification for the foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        val smsForwardServiceEnabled = intent?.getBooleanExtra("SMS_FORWARD_SERVICE_ENABLED", false) ?: false

        if (smsForwardServiceEnabled) {
            startListeningForSms()
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListeningForSms()
        Log.d("SmsForwarderService", "Service destroyed.")
    }

    private fun startListeningForSms() {
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            registerReceiver(smsReceiver, intentFilter)
            isReceiverRegistered = true // Update the registration status
            Log.d("SmsForwarderService", "Started listening for SMS.")
        }
    }

    private fun stopListeningForSms() {
        if (isReceiverRegistered) {
            unregisterReceiver(smsReceiver)
            isReceiverRegistered = false // Update the registration status
            Log.d("SmsForwarderService", "Stopped listening for SMS.")
        }
    }

    private fun createNotification(): Notification {
        Log.d("SmsForwarderService", "Creating notification...")
        // Create a notification channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "sms_forwarder_channel"
            val channelName = "SMS Forwarder Service"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Build the notification
        return Notification.Builder(this, "sms_forwarder_channel")
            .setContentTitle("SMS Forwarder Service")
            .setContentText("Listening for SMS...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
