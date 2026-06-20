package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Base64
import java.util.UUID

class DeviceRepository(
    private val db: FirebaseFirestore = FirebaseProvider.db,
    private val crypto: CryptoManager,
    private val prefs: UserPreferences,
) {
    suspend fun registerThisDevice(ownerEmail: String, alias: String): String {
        crypto.ensureIdentityKey()
        var id = prefs.cloudDeviceId.first()
        if (id.isBlank()) { id = UUID.randomUUID().toString(); prefs.saveCloudDeviceId(id) }
        val pub = Base64.getEncoder().encodeToString(crypto.publicKeyset())
        db.collection("devices").document(id).set(
            mapOf(
                "ownerEmail" to ownerEmail,
                "alias" to alias,
                "publicKey" to pub,
                "keyVersion" to crypto.currentVersion(),
                "revoked" to false,
            ),
            SetOptions.merge(),
        ).await()
        return id
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
