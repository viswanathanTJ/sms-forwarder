package com.viswa2k.smsforwarder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viswa2k.smsforwarder.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the service after a reboot or an app update, and reports a reboot as an info alert. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = AppGraph.from(context)
        WatchdogWorker.ensure(context)
        val pending = goAsync()
        graph.appScope.launch {
            try {
                if (graph.settings.masterOn.first()) ForwarderService.start(context)
                if (action == Intent.ACTION_BOOT_COMPLETED) ProblemAlerter(graph).info("REBOOTED — phone restarted")
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
