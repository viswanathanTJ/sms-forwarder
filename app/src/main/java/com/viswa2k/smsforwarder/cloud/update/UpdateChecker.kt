package com.viswa2k.smsforwarder.cloud.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** A newer GitHub release than the running build. */
data class UpdateInfo(
    val version: String,      // e.g. "1.2.0" (tag without the leading 'v')
    val notes: String,        // release body / changelog
    val apkUrl: String,       // browser_download_url of the .apk asset
    val apkName: String,      // asset file name, e.g. sms-forwarder-1.2.0.apk
)

/**
 * Checks the public GitHub Releases API for a newer version and exposes the APK
 * to install. Version comparison is pure and unit-tested.
 */
object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/viswanathanTJ/sms-forwarder/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val body: String? = null,
        @SerialName("prerelease") val prerelease: Boolean = false,
        @SerialName("draft") val draft: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    )

    /** Numeric semver-ish comparison; ignores any non-numeric suffix like "-debug". */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numericParts(latest)
        val b = numericParts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        Regex("\\d+").findAll(version.removePrefix("v")).map { it.value.toInt() }.toList()

    /** Returns details of a newer release, or null if up to date / unavailable. */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = try {
            fetchLatest()
        } catch (e: Exception) {
            Log.w("UpdateChecker", "Update check failed: ${e.message}")
            return@withContext null
        }
        if (release == null || release.draft || release.prerelease) return@withContext null

        val latest = release.tagName.removePrefix("v")
        if (latest.isBlank() || !isNewer(latest, currentVersion)) return@withContext null

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null

        UpdateInfo(
            version = latest,
            notes = release.body.orEmpty(),
            apkUrl = apk.browserDownloadUrl,
            apkName = apk.name,
        )
    }

    private fun fetchLatest(): GhRelease? {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(GhRelease.serializer(), text)
        } finally {
            conn.disconnect()
        }
    }
}
