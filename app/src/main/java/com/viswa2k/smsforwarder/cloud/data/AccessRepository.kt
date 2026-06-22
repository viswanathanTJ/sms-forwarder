package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Permanently remove a device and ALL of its cloud data (admin-only cleanup).
     * Each cleanup step is best-effort so a single failure (e.g. a collection-group
     * query that's blocked or unindexed) never prevents the device doc itself from
     * being deleted.
     */
    suspend fun removeDeviceAndData(deviceId: String) {
        // Delete the device doc FIRST — it's the essential operation, and it must not be
        // blocked by data-cleanup steps. (A denied collection-group query doesn't throw
        // promptly; the SDK retries the listen and the call can hang, so each cleanup is
        // both best-effort and time-bounded.)
        runCatching { db.collection("devices").document(deviceId).delete().await() }
        cleanup { deleteQuery(db.collection("inbox").document(deviceId).collection("messages")) } // received
        for (field in listOf("readerDeviceId", "sourceDeviceId")) {
            cleanup { deleteQuery(db.collection("access_matrix").whereEqualTo(field, deviceId)) }
            cleanup { deleteQuery(db.collection("subscriptions").whereEqualTo(field, deviceId)) }
        }
        // Sent messages live in other inboxes; purging them needs the admin collection-group
        // read rule. Best-effort + bounded so a denial can never hang the removal.
        cleanup { deleteQuery(db.collectionGroup("messages").whereEqualTo("sourceDeviceId", deviceId)) }
    }

    /** Best-effort, time-bounded cleanup step — never throws, never hangs the caller. */
    private suspend fun cleanup(block: suspend () -> Unit) {
        runCatching { withTimeoutOrNull(8000) { block() } }
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
