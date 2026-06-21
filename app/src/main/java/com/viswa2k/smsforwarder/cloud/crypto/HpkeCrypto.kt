package com.viswa2k.smsforwarder.cloud.crypto

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure Tink HPKE (RFC 9180) envelope + AES-256-GCM body crypto. JVM-testable. */
object HpkeCrypto {
    const val HPKE_TEMPLATE = "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val rng = SecureRandom() // thread-safe: SecureRandom.nextBytes is synchronized

    init { HybridConfig.register() }

    fun generatePrivateKeyset(): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(HPKE_TEMPLATE))

    /** WARNING: returns UNENCRYPTED private key bytes. Callers must seal it (e.g. via
     *  Android Keystore in CryptoManager) before persisting or transmitting. */
    fun serializePrivateKeyset(handle: KeysetHandle): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    fun deserializePrivateKeyset(bytes: ByteArray): KeysetHandle =
        CleartextKeysetHandle.read(BinaryKeysetReader.withInputStream(ByteArrayInputStream(bytes)))

    fun serializePublicKeyset(handle: KeysetHandle): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle.publicKeysetHandle, BinaryKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    fun seal(recipientPublicKeyset: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray {
        val pub = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(recipientPublicKeyset))
        return pub.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
    }

    fun open(privateKeyset: KeysetHandle, ciphertext: ByteArray, contextInfo: ByteArray): ByteArray =
        privateKeyset.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)

    fun newDek(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    data class EncryptedBody(val ciphertext: ByteArray, val nonce: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedBody) return false
            return ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce)
        }
        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + nonce.contentHashCode()
    }

    fun encryptBody(dek: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): EncryptedBody {
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return EncryptedBody(cipher.doFinal(plaintext), iv)
    }

    fun decryptBody(dek: ByteArray, ciphertext: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** Best-effort wipe of sensitive key bytes from memory. */
    fun wipe(vararg secrets: ByteArray) {
        for (s in secrets) s.fill(0)
    }
}
