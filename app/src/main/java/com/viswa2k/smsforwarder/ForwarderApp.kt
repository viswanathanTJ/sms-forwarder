package com.viswa2k.smsforwarder

import android.app.Application
import android.util.Log
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import com.viswa2k.smsforwarder.service.ForwarderService
import com.viswa2k.smsforwarder.service.WatchdogWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // runOnce never throws and always opens the gate that startup readers await.
        graph.appScope.launch {
            V1MigrationRunner.runOnce(graph)
            ensureRunning()
        }
    }

    /**
     * Spec 5.8: opening the app always (re)starts the service (which runs the catch-up scan) when
     * forwarding is on. Safe off the main thread and idempotent; also called from MainActivity.onResume.
     */
    fun ensureRunning() {
        graph.appScope.launch {
            try {
                V1MigrationRunner.awaitDone(graph)
                WatchdogWorker.ensure(this@ForwarderApp)
                if (graph.settings.masterOn.first()) ForwarderService.start(this@ForwarderApp)
            } catch (e: Exception) {
                Log.w("ForwarderApp", "ensureRunning failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
