package com.viswa2k.smsforwarder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.MainActivity
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import com.viswa2k.smsforwarder.pipeline.InboxCatchUp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quiet foreground service (spec 5.8): keeps the process protected on aggressive OEM ROMs. It does
 * no periodic work; reception stays in the manifest [com.viswa2k.smsforwarder.pipeline.SmsReceiver].
 */
class ForwarderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            promote()
        } catch (e: Exception) {
            // Not allowed to be foreground right now (e.g. background start restrictions): the watchdog retries.
            Log.w(TAG, "startForeground refused: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        WatchdogWorker.ensure(applicationContext)
        val graph = AppGraph.from(this)
        graph.appScope.launch {
            try {
                V1MigrationRunner.awaitDone(graph)
                if (!graph.settings.masterOn.first()) {
                    // Switched off: no forwarding, no restart. (Already promoted, as startForegroundService requires.)
                    stopSelf()
                    return@launch
                }
                val now = System.currentTimeMillis()
                InboxCatchUp(graph).run(now)
                graph.dispatcher.sendDue(now)
                ProblemAlerter(graph).check(now)
            } catch (e: Exception) {
                Log.e(TAG, "startup pass failed: ${e.javaClass.simpleName}")
            }
        }
        return START_STICKY
    }

    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Forwarder status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Forwarding SMS · tap to open")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = applicationContext
        val graph = AppGraph.from(app)
        graph.appScope.launch {
            try {
                if (graph.settings.masterOn.first()) start(app)
            } catch (e: Exception) {
                Log.w(TAG, "restart check failed: ${e.javaClass.simpleName}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "ForwarderService"
        const val CHANNEL_ID = "forwarder_status"
        private const val NOTIFICATION_ID = 1

        /** Idempotent. Never throws: a refused start falls back to the 15-min watchdog. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, ForwarderService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) or similar.
                Log.w(TAG, "service start refused: ${e.javaClass.simpleName}")
                WatchdogWorker.ensure(app)
            }
        }
    }
}
