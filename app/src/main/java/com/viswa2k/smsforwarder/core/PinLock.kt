package com.viswa2k.smsforwarder.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * Optional Settings PIN (spec 5.8, "Locked for the elder"). Stored as `hex(salt):hex(SHA-256(salt + PIN))`
 * with a random per-install salt, so the 10^4–10^6 PIN space cannot be looked up in a precomputed table.
 */
object PinLock {
    private const val SALT_BYTES = 16
    private val PIN = Regex("^[0-9]{4,6}$")

    fun isValid(pin: String): Boolean = PIN.matches(pin)

    fun newRecord(pin: String, random: Random = SecureRandom()): String =
        record(pin, ByteArray(SALT_BYTES).also { random.nextBytes(it) })

    fun record(pin: String, salt: ByteArray): String {
        require(isValid(pin)) { "PIN must be 4 to 6 digits" }
        return "${salt.hex()}:${digest(salt, pin).hex()}"
    }

    fun verify(pin: String, stored: String): Boolean {
        if (!isValid(pin)) return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].unhex() ?: return false
        val expected = parts[1].unhex() ?: return false
        if (salt.isEmpty() || expected.size != 32) return false
        return MessageDigest.isEqual(digest(salt, pin), expected)
    }

    private fun digest(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(pin.toByteArray(Charsets.UTF_8))
        }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
