package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayload
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

class CloudMessageRepository(
    private val db: FirebaseFirestore = FirebaseProvider.db,
    private val crypto: CryptoManager,
    private val deviceRepo: DeviceRepository,
    private val accessRepo: AccessRepository,
) {
    @Serializable data class RecipientCopy(val recipientDeviceId: String, val wrappedDekB64: String)

    @Serializable data class FanOut(
        val messageId: String,
        val sourceDeviceId: String,
        val sourceAlias: String,
        val ciphertextB64: String,
        val nonceB64: String,
        val copies: List<RecipientCopy>,
    )

    data class DecryptedMessage(
        val id: String,
        val sourceDeviceId: String,
        val sourceAlias: String,
        val sender: String,
        val body: String,
        val originalTimestamp: Long,
        val uploadedAt: String,
    )

    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    suspend fun buildFanOut(sourceDeviceId: String, sourceAlias: String, payload: CloudSmsPayload): FanOut {
        val matrix = accessRepo.listAccessMatrix()
        val adminIds = deviceRepo.adminDeviceIds()
        val recipientIds = RecipientSelector.recipientDeviceIds(sourceDeviceId, matrix, adminIds)
        val keyByDevice = deviceRepo.fetchFleetDevices()
            .filter { !it.revoked && it.publicKey.isNotBlank() }
            .associate { it.id to it.publicKey }

        val dek = crypto.newDek()
        val body = crypto.encryptBody(dek, payload.toJsonBytes())
        val copies = recipientIds.mapNotNull { id ->
            keyByDevice[id]?.let { pub ->
                RecipientCopy(id, enc.encodeToString(crypto.sealDekTo(dec.decode(pub), dek)))
            }
        }
        return FanOut(
            messageId = UUID.randomUUID().toString(),
            sourceDeviceId = sourceDeviceId,
            sourceAlias = sourceAlias,
            ciphertextB64 = enc.encodeToString(body.ciphertext),
            nonceB64 = enc.encodeToString(body.nonce),
            copies = copies,
        )
    }

    suspend fun pushFanOut(fanOut: FanOut) {
        if (fanOut.copies.isEmpty()) return
        val batch = db.batch()
        for (c in fanOut.copies) {
            val ref = db.collection("inbox").document(c.recipientDeviceId)
                .collection("messages").document(fanOut.messageId)
            batch.set(ref, mapOf(
                "messageId" to fanOut.messageId,
                "sourceDeviceId" to fanOut.sourceDeviceId,
                "sourceAlias" to fanOut.sourceAlias,
                "ciphertext" to fanOut.ciphertextB64,
                "nonce" to fanOut.nonceB64,
                "wrappedDek" to c.wrappedDekB64,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
        }
        batch.commit().await()
    }

    suspend fun uploadEncrypted(sourceDeviceId: String, sourceAlias: String, payload: CloudSmsPayload) =
        pushFanOut(buildFanOut(sourceDeviceId, sourceAlias, payload))

    suspend fun listForReader(readerDeviceId: String, aliases: Map<String, String>): List<DecryptedMessage> =
        db.collection("inbox").document(readerDeviceId).collection("messages")
            .get().await().documents.mapNotNull { decrypt(it, aliases) }
            .sortedByDescending { it.originalTimestamp }

    suspend fun decryptOne(readerDeviceId: String, messageId: String, aliases: Map<String, String>): DecryptedMessage? {
        val doc = db.collection("inbox").document(readerDeviceId).collection("messages")
            .document(messageId).get().await()
        if (!doc.exists()) return null
        return decrypt(doc, aliases)
    }

    private fun decrypt(doc: DocumentSnapshot, aliases: Map<String, String>): DecryptedMessage? = try {
        val dek = crypto.openWrappedDek(dec.decode(doc.getString("wrappedDek")!!))
        val plain = crypto.decryptBody(dek, dec.decode(doc.getString("ciphertext")!!), dec.decode(doc.getString("nonce")!!))
        val payload = CloudSmsPayload.fromJsonBytes(plain)
        val source = doc.getString("sourceDeviceId") ?: ""
        DecryptedMessage(
            id = doc.getString("messageId") ?: doc.id,
            sourceDeviceId = source,
            sourceAlias = aliases[source] ?: doc.getString("sourceAlias") ?: payload.deviceAlias,
            sender = payload.sender,
            body = payload.body,
            originalTimestamp = payload.originalTimestamp,
            uploadedAt = doc.getTimestamp("createdAt")?.toDate()?.toString() ?: "",
        )
    } catch (e: Exception) {
        null // cannot decrypt (e.g., sealed before this device's key) — skip, don't crash
    }

    private suspend fun deleteAll(docs: List<DocumentSnapshot>) {
        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    suspend fun deleteMessage(messageId: String) {
        deleteAll(db.collectionGroup("messages").whereEqualTo("messageId", messageId).get().await().documents)
    }

    suspend fun deleteAllForSource(sourceDeviceId: String) {
        deleteAll(db.collectionGroup("messages").whereEqualTo("sourceDeviceId", sourceDeviceId).get().await().documents)
    }
}
