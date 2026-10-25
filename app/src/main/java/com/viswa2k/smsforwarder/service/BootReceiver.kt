package com.viswa2k.smsforwarder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.viswa2k.smsforwarder.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the service after a reboot or an app update. A reboot is recorded as pending and reported
 * by [ProblemAlerter.check] once Telegram is reachable (the network is usually not up yet at boot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = AppGraph.from(context)
        val now = System.currentTimeMillis()
        WatchdogWorker.ensure(context)
        val pending = goAsync()
        graph.appScope.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED) graph.settings.setPendingReboot(now)
                if (graph.settings.masterOn.first()) ForwarderService.start(context)
                ProblemAlerter(graph).check(now)
            } catch (e: Exception) {
                Log.w("BootReceiver", "boot handling failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
