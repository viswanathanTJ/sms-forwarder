package com.viswa2k.smsforwarder.cloud.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayload
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.flow.first
import java.io.File

class SmsCloudUploader private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = UserPreferences(appContext.dataStore)
    private val crypto = CryptoManager(appContext)
    private val deviceRepo = DeviceRepository(context = appContext, crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)
    private val queue = CloudUploadQueue(File(appContext.filesDir, "cloud_upload_queue"))

    suspend fun upload(senderNumber: String, body: String, timestamp: Long, force: Boolean = false) {
        if (!force && !prefs.isCloudChannelEnabled.first()) return
        val deviceId = prefs.cloudDeviceId.first()
        if (deviceId.isBlank()) {
            Log.w("SmsCloudUploader", "Cloud enabled but device not registered; skipping")
            return
        }
        var alias = prefs.deviceAlias.first()
        if (alias.isBlank()) alias = "${Build.MANUFACTURER} ${Build.MODEL}"
        val payload = CloudSmsPayload(senderNumber, body, timestamp, alias)
        val fanOut = try {
            messageRepo.buildFanOut(deviceId, alias, payload)
        } catch (e: Exception) {
            Log.e("SmsCloudUploader", "Encrypt failed; cannot queue without recipients")
            return
        }
        if (fanOut.copies.isEmpty()) {
            Log.e("SmsCloudUploader", "No resolvable recipient keys (admin/readers not registered?); message NOT uploaded")
            return
        }
        try {
            messageRepo.pushFanOut(fanOut)
        } catch (e: Exception) {
            Log.w("SmsCloudUploader", "Upload failed; queued for retry")
            queue.enqueue(fanOut)
        }
    }

    suspend fun flushQueue() {
        if (!prefs.isCloudChannelEnabled.first()) return
        var consecutiveFailures = 0
        for (f in queue.pending()) {
            try {
                messageRepo.pushFanOut(f)
                queue.remove(f)
                consecutiveFailures = 0
            } catch (e: Exception) {
                // Skip a single bad item (don't head-of-line block), but stop after a few
                // failures in a row — that means we're offline; retry on the next flush.
                consecutiveFailures++
                Log.w("SmsCloudUploader", "Retry failed; will try later")
                if (consecutiveFailures >= 3) break
            }
        }
    }

    companion object {
        @Volatile private var instance: SmsCloudUploader? = null

        /** App-scoped singleton: the encryption/repository stack is built once and reused. */
        fun get(context: Context): SmsCloudUploader =
            instance ?: synchronized(this) {
                instance ?: SmsCloudUploader(context.applicationContext).also { instance = it }
            }
    }
}
