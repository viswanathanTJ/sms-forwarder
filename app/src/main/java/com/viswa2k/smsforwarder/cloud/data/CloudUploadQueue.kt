package com.viswa2k.smsforwarder.cloud.data

import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed retry queue for encrypted fan-outs. Persists ONLY ciphertext + wrapped DEKs.
 * Bounded to [MAX_ITEMS]: enqueuing past the cap evicts the oldest entry, so a persistent
 * failure (a "poison" item or a long outage) can never grow the queue without limit.
 */
class CloudUploadQueue(private val dir: File, private val maxItems: Int = MAX_ITEMS) {
    init { if (!dir.exists()) dir.mkdirs() }
    private val json = Json { encodeDefaults = true }

    fun enqueue(f: CloudMessageRepository.FanOut) {
        File(dir, "${f.messageId}.json").writeText(json.encodeToString(CloudMessageRepository.FanOut.serializer(), f))
        evictOldestBeyondCap()
    }

    private fun evictOldestBeyondCap() {
        val files = (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
        if (files.size > maxItems) files.take(files.size - maxItems).forEach { it.delete() }
    }

    companion object { const val MAX_ITEMS = 200 }

    fun pending(): List<CloudMessageRepository.FanOut> =
        (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { json.decodeFromString(CloudMessageRepository.FanOut.serializer(), it.readText()) }.getOrNull() }

    fun remove(f: CloudMessageRepository.FanOut) { File(dir, "${f.messageId}.json").delete() }
}
