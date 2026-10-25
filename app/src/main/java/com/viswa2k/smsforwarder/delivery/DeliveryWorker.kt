package com.viswa2k.smsforwarder.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viswa2k.smsforwarder.AppGraph

/** Runs one outbox pass; [Dispatcher] reschedules itself when rows stay pending. */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.from(applicationContext).dispatcher.sendDue(System.currentTimeMillis(), fromWorker = true)
        return Result.success()
    }
}
