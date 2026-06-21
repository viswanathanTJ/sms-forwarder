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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SmsForwarderService : Service() {

    private lateinit var smsReceiver: SmsReceiver
    private var isReceiverRegistered = false // Track receiver registration status

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("SmsForwarderService", "Service created.")
        smsReceiver = SmsReceiver()

        // Flush any queued cloud uploads as soon as the network comes back, instead of
        // waiting for the next app launch / sign-in.
        registerConnectivityFlush()

        // Reset retry count on successful service creation
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("retry_count", 0)
            .apply()
    }

    private fun registerConnectivityFlush() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    runCatching { SmsCloudUploader.get(applicationContext).flushQueue() }
                        .onFailure { Log.w("SmsForwarderService", "Connectivity flush failed", it) }
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w("SmsForwarderService", "registerNetworkCallback failed", it) }
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
        
        // Exact alarms require SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM on API 31+ and throw
        // SecurityException otherwise. Use exact when permitted, else fall back to an
        // inexact (but idle-tolerant) alarm so the service-restart heartbeat never crashes.
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, delayMillis, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, delayMillis, pendingIntent
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, delayMillis, pendingIntent
                )
            }
            else -> {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, delayMillis, pendingIntent
                )
            }
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
        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        serviceScope.cancel()
        Log.d("SmsForwarderService", "Service destroyed.")
    }

    private fun startListeningForSms() {
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            ContextCompat.registerReceiver(this, smsReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
