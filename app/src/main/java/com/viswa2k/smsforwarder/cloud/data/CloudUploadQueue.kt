package com.viswa2k.smsforwarder.cloud.data

import kotlinx.serialization.json.Json
import java.io.File

/** File-backed retry queue for encrypted fan-outs. Persists ONLY ciphertext + wrapped DEKs. */
class CloudUploadQueue(private val dir: File) {
    init { if (!dir.exists()) dir.mkdirs() }
    private val json = Json { encodeDefaults = true }

    fun enqueue(f: CloudMessageRepository.FanOut) {
        File(dir, "${f.messageId}.json").writeText(json.encodeToString(CloudMessageRepository.FanOut.serializer(), f))
    }

    fun pending(): List<CloudMessageRepository.FanOut> =
        (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { json.decodeFromString(CloudMessageRepository.FanOut.serializer(), it.readText()) }.getOrNull() }

    fun remove(f: CloudMessageRepository.FanOut) { File(dir, "${f.messageId}.json").delete() }
}
