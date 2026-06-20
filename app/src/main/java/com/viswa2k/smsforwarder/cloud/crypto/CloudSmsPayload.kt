package com.viswa2k.smsforwarder.cloud.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CloudSmsPayload(
    val sender: String,
    val body: String,
    val originalTimestamp: Long,
    val deviceAlias: String,
) {
    fun toJsonBytes(): ByteArray = Json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)
    companion object {
        fun fromJsonBytes(bytes: ByteArray): CloudSmsPayload =
            Json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}
