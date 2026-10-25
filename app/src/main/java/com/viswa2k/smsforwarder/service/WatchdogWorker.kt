package com.viswa2k.smsforwarder.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner
import com.viswa2k.smsforwarder.delivery.Dispatcher
import com.viswa2k.smsforwarder.pipeline.InboxCatchUp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 15-minute safety net. No network constraint: catch-up must also run offline and the dispatcher
 * must be able to mark rows older than 24 h as FAILED (sends just fail and retry offline).
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        V1MigrationRunner.awaitDone(graph)
        val now = System.currentTimeMillis()
        if (graph.settings.masterOn.first()) ForwarderService.start(applicationContext)
        step("catch-up") { InboxCatchUp(graph).run(now) }
        step("dispatch") { graph.dispatcher.sendDue(now) }
        step("schedule") { Dispatcher.schedule(applicationContext) }
        step("prune") { graph.logs.prune(now) }
        step("alerts") { ProblemAlerter(graph).check(now) }
        return Result.success()
    }

    /** One failing step must not stop the others. */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WatchdogWorker", "$name failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val WORK_NAME = "watchdog"

        fun ensure(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
