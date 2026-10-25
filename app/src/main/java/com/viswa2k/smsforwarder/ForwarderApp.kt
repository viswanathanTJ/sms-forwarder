package com.viswa2k.smsforwarder

import android.app.Application
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import kotlinx.coroutines.launch

class ForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // runOnce never throws and always opens the gate that startup readers await.
        graph.appScope.launch { V1MigrationRunner.runOnce(graph) }
    }
}
