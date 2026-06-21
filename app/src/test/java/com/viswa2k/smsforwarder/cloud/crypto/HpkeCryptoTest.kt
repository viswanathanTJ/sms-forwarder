package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HpkeCryptoTest {

    @Test
    fun sealThenOpen_roundTrips() {
        val recipient = HpkeCrypto.generatePrivateKeyset()
        val pub = HpkeCrypto.serializePublicKeyset(recipient)
        val msg = "Your OTP is 4471".toByteArray()
        val sealed = HpkeCrypto.seal(pub, msg, contextInfo = ByteArray(0))
        assertArrayEquals(msg, HpkeCrypto.open(recipient, sealed, ByteArray(0)))
    }

    @Test
    fun wrongRecipient_cannotOpen() {
        val a = HpkeCrypto.generatePrivateKeyset()
        val b = HpkeCrypto.generatePrivateKeyset()
        val sealed = HpkeCrypto.seal(HpkeCrypto.serializePublicKeyset(a), "secret".toByteArray(), ByteArray(0))
        var failed = false
        try { HpkeCrypto.open(b, sealed, ByteArray(0)) } catch (e: java.security.GeneralSecurityException) { failed = true }
        assertTrue("device B must not decrypt A's envelope", failed)
    }

    @Test
    fun body_encryptDecrypt_roundTrips() {
        val dek = HpkeCrypto.newDek()
        val plain = "sender=+100;body=hello".toByteArray()
        val enc = HpkeCrypto.encryptBody(dek, plain)
        assertArrayEquals(plain, HpkeCrypto.decryptBody(dek, enc.ciphertext, enc.nonce))
    }

    @Test
    fun body_aad_roundTripsAndRejectsMismatch() {
        val dek = HpkeCrypto.newDek()
        val plain = "OTP 4471".toByteArray()
        val aad = "src|msg123".toByteArray()
        val enc = HpkeCrypto.encryptBody(dek, plain, aad)
        // Correct AAD decrypts.
        assertArrayEquals(plain, HpkeCrypto.decryptBody(dek, enc.ciphertext, enc.nonce, aad))
        // Tampered AAD (e.g. re-attributing the message) must fail the GCM tag check.
        var failed = false
        try { HpkeCrypto.decryptBody(dek, enc.ciphertext, enc.nonce, "src|other".toByteArray()) }
        catch (e: Exception) { failed = true }
        assertTrue("decrypt must reject a different AAD", failed)
    }

    @Test
    fun privateKeyset_serializeRoundTrips() {
        val handle = HpkeCrypto.generatePrivateKeyset()
        val restored = HpkeCrypto.deserializePrivateKeyset(HpkeCrypto.serializePrivateKeyset(handle))
        val sealed = HpkeCrypto.seal(HpkeCrypto.serializePublicKeyset(handle), "x".toByteArray(), ByteArray(0))
        assertArrayEquals("x".toByteArray(), HpkeCrypto.open(restored, sealed, ByteArray(0)))
    }
}
