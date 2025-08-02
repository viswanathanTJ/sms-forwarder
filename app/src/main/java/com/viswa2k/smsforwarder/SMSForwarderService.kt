package com.viswa2k.smsforwarder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsForwarderService : Service() {

    private lateinit var smsReceiver: SmsReceiver
    private var isReceiverRegistered = false // Track receiver registration status

    override fun onCreate() {
        super.onCreate()
        Log.d("SmsForwarderService", "Service created.")
        smsReceiver = SmsReceiver()
        
        // Reset retry count on successful service creation
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("retry_count", 0)
            .apply()
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

        // Schedule a restart if the service gets killed
        scheduleServiceRestart()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart the service if it's killed
        scheduleServiceRestart()
    }

    private fun scheduleServiceRestart() {
        val restartIntent = Intent("${packageName}.RESTART_SERVICE")
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Use exponential backoff for restart attempts
        val retryCount = getRetryCount()
        val delayMillis = calculateBackoffDelay(retryCount)
        
        // Use setAlarmClock for API 23+ to ensure precise delivery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                delayMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                delayMillis,
                pendingIntent
            )
        }
        
        // Increment retry count
        incrementRetryCount()
    }
    
    private fun getRetryCount(): Int {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("retry_count", 0)
    }
    
    private fun incrementRetryCount() {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("retry_count", 0)
        prefs.edit().putInt("retry_count", currentCount + 1).apply()
    }
    
    private fun calculateBackoffDelay(retryCount: Int): Long {
        // Base delay of 5 minutes
        val baseDelay = 5 * 60 * 1000L
        // Max delay of 4 hours
        val maxDelay = 4 * 60 * 60 * 1000L
        
        // Calculate exponential backoff with a maximum
        // Less aggressive restart schedule
        val delay = baseDelay * (1 shl minOf(retryCount, 4))
        return minOf(delay, maxDelay)
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
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.privacy_policy_summary)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent for notification click
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        return Notification.Builder(this, "sms_forwarder_channel")
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
