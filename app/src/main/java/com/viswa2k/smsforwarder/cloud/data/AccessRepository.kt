package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class AccessRepository(private val db: FirebaseFirestore = FirebaseProvider.db) {

    // --- admin ---
    suspend fun listAuthorizedEmails(): List<AuthorizedEmail> =
        db.collection("authorized_emails").get().await().documents
            .map { AuthorizedEmail(it.id, it.getString("role") ?: "member") }

    suspend fun addAuthorizedEmail(email: String, role: String, addedBy: String) {
        db.collection("authorized_emails").document(email)
            .set(mapOf("role" to role, "addedBy" to addedBy)).await()
    }

    suspend fun removeAuthorizedEmail(email: String) {
        db.collection("authorized_emails").document(email).delete().await()
    }

    // --- access requests (admin review of self-service signups) ---
    suspend fun listAccessRequests(): List<AccessRequest> =
        db.collection("access_requests").get().await().documents
            .map { AccessRequest(it.id, it.getString("displayName") ?: "") }

    suspend fun approveRequest(email: String, approvedBy: String) {
        db.collection("authorized_emails").document(email)
            .set(mapOf("role" to "member", "addedBy" to approvedBy)).await()
        db.collection("access_requests").document(email).delete().await()
    }

    suspend fun denyRequest(email: String) {
        db.collection("access_requests").document(email).delete().await()
    }

    suspend fun listAccessMatrix(): List<AccessGrant> =
        db.collection("access_matrix").get().await().documents
            .map { AccessGrant(it.getString("readerDeviceId") ?: "", it.getString("sourceDeviceId") ?: "") }

    suspend fun grantAccess(readerDeviceId: String, sourceDeviceId: String, grantedBy: String) {
        db.collection("access_matrix").document(pairId(readerDeviceId, sourceDeviceId))
            .set(mapOf("readerDeviceId" to readerDeviceId, "sourceDeviceId" to sourceDeviceId, "grantedBy" to grantedBy)).await()
    }

    suspend fun revokeAccess(readerDeviceId: String, sourceDeviceId: String) {
        db.collection("access_matrix").document(pairId(readerDeviceId, sourceDeviceId)).delete().await()
    }

    suspend fun setDeviceRevoked(deviceId: String, revoked: Boolean) {
        db.collection("devices").document(deviceId).set(mapOf("revoked" to revoked), SetOptions.merge()).await()
    }

    /** Permanently remove a device and ALL of its cloud data (admin-only cleanup). */
    suspend fun removeDeviceAndData(deviceId: String) {
        deleteQuery(db.collection("inbox").document(deviceId).collection("messages")) // messages it received
        deleteQuery(db.collectionGroup("messages").whereEqualTo("sourceDeviceId", deviceId)) // messages it sent
        for (field in listOf("readerDeviceId", "sourceDeviceId")) {
            deleteQuery(db.collection("access_matrix").whereEqualTo(field, deviceId))
            deleteQuery(db.collection("subscriptions").whereEqualTo(field, deviceId))
        }
        db.collection("devices").document(deviceId).delete().await()
    }

    private suspend fun deleteQuery(query: Query) {
        val docs = query.get().await().documents
        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    // --- reader ---
    suspend fun allowedSources(readerDeviceId: String): List<String> =
        db.collection("access_matrix").whereEqualTo("readerDeviceId", readerDeviceId)
            .get().await().documents.mapNotNull { it.getString("sourceDeviceId") }

    suspend fun listSubscriptions(readerDeviceId: String): List<Subscription> =
        db.collection("subscriptions").whereEqualTo("readerDeviceId", readerDeviceId)
            .get().await().documents.map {
                Subscription(it.getString("readerDeviceId") ?: "", it.getString("sourceDeviceId") ?: "", it.getBoolean("notify") ?: true)
            }

    suspend fun subscribe(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) {
        db.collection("subscriptions").document(pairId(readerDeviceId, sourceDeviceId))
            .set(mapOf("readerDeviceId" to readerDeviceId, "sourceDeviceId" to sourceDeviceId, "notify" to notify)).await()
    }

    suspend fun setNotify(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) =
        subscribe(readerDeviceId, sourceDeviceId, notify)

    suspend fun unsubscribe(readerDeviceId: String, sourceDeviceId: String) {
        db.collection("subscriptions").document(pairId(readerDeviceId, sourceDeviceId)).delete().await()
    }
}
