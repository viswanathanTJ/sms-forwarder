package com.viswa2k.smsforwarder.cloud.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Base64
import java.util.UUID

class DeviceRepository(
    private val context: Context,
    private val crypto: CryptoManager,
    private val prefs: UserPreferences,
    private val db: FirebaseFirestore = FirebaseProvider.db,
) {
    suspend fun registerThisDevice(ownerEmail: String, alias: String): String {
        crypto.ensureIdentityKey()
        // Stable per-physical-device id (survives reinstall/login), so one device = one entry
        // instead of a fresh random UUID on every install.
        val existing = prefs.cloudDeviceId.first()
        val id = stableDeviceId() ?: existing.ifBlank { UUID.randomUUID().toString() }
        if (existing != id) prefs.saveCloudDeviceId(id)
        val pub = Base64.getEncoder().encodeToString(crypto.publicKeyset())
        db.collection("devices").document(id).set(
            mapOf(
                "ownerEmail" to ownerEmail,
                "alias" to alias,
                "publicKey" to pub,
                "keyVersion" to crypto.currentVersion(),
            ),
            SetOptions.merge(),
        ).await()
        return id
    }

    /**
     * A stable identifier for this physical device + app install signing key. Unlike a random
     * UUID it survives uninstall/reinstall and re-login, so the same device maps to one doc.
     * Returns null on the rare devices where ANDROID_ID is missing/the known-buggy value, so the
     * caller can fall back to a persisted UUID.
     */
    @SuppressLint("HardwareIds")
    private fun stableDeviceId(): String? {
        val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (!aid.isNullOrBlank() && aid != "9774d56d682e549c") "android-$aid" else null
    }

    suspend fun updateFcmToken(token: String) {
        val id = prefs.cloudDeviceId.first()
        if (id.isBlank()) return
        db.collection("devices").document(id).set(mapOf("fcmToken" to token), SetOptions.merge()).await()
    }

    suspend fun fetchFleetDevices(): List<Device> =
        db.collection("devices").get().await().documents.map { d ->
            Device(
                id = d.id,
                ownerEmail = d.getString("ownerEmail") ?: "",
                alias = d.getString("alias") ?: "",
                publicKey = d.getString("publicKey") ?: "",
                revoked = d.getBoolean("revoked") ?: false,
                fcmToken = d.getString("fcmToken"),
            )
        }

    suspend fun adminDeviceIds(): Set<String> {
        val adminEmails = db.collection("authorized_emails").whereEqualTo("role", "admin")
            .get().await().documents.map { it.id }.toSet()
        if (adminEmails.isEmpty()) return emptySet()
        return fetchFleetDevices().filter { it.ownerEmail in adminEmails }.map { it.id }.toSet()
    }
}
