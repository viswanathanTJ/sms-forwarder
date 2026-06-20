package com.viswa2k.smsforwarder.cloud.data

/** Firestore document-id for the reader→source pair collections. */
fun pairId(readerDeviceId: String, sourceDeviceId: String): String = "${readerDeviceId}__${sourceDeviceId}"

data class Device(
    val id: String,
    val ownerEmail: String,
    val alias: String,
    val publicKey: String,
    val revoked: Boolean,
    val fcmToken: String?,
)

data class AuthorizedEmail(val email: String, val role: String)
data class AccessGrant(val readerDeviceId: String, val sourceDeviceId: String)
data class Subscription(val readerDeviceId: String, val sourceDeviceId: String, val notify: Boolean)
