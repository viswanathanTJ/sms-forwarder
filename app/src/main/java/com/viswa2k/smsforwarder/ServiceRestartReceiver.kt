package com.viswa2k.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "${context.packageName}.RESTART_SERVICE" -> {
                // Start the service
                val serviceIntent = Intent(context, SmsForwarderService::class.java).apply {
                    putExtra("SMS_FORWARD_SERVICE_ENABLED", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
