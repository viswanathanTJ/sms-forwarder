package com.viswa2k.smsforwarder.cloud.crypto

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class CryptoManager(context: Context) {
    private val appContext = context.applicationContext
    init { HybridConfig.register() }

    private fun manager(): AndroidKeysetManager =
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()

    private fun handle(): KeysetHandle = manager().keysetHandle

    fun ensureIdentityKey() { manager() }

    fun publicKeyset(): ByteArray = HpkeCrypto.serializePublicKeyset(handle())

    fun currentVersion(): Int =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getInt(VERSION_KEY, 1)

    fun rotateIdentityKey(): Int {
        val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val next = currentVersion() + 1
        manager().add(KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
        prefs.edit().putInt(VERSION_KEY, next).apply()
        return next
    }

    fun newDek(): ByteArray = HpkeCrypto.newDek()
    fun sealDekTo(recipientPublicKeyset: ByteArray, dek: ByteArray, context: ByteArray = ByteArray(0)): ByteArray =
        HpkeCrypto.seal(recipientPublicKeyset, dek, context)
    fun openWrappedDek(wrappedDek: ByteArray, context: ByteArray = ByteArray(0)): ByteArray =
        HpkeCrypto.open(handle(), wrappedDek, context)
    fun encryptBody(dek: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): HpkeCrypto.EncryptedBody =
        HpkeCrypto.encryptBody(dek, plaintext, aad)
    fun decryptBody(dek: ByteArray, ciphertext: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        HpkeCrypto.decryptBody(dek, ciphertext, nonce, aad)
    fun wipe(vararg secrets: ByteArray) = HpkeCrypto.wipe(*secrets)

    companion object {
        private const val KEYSET_NAME = "cloud_identity_keyset"
        private const val PREF_FILE = "cloud_identity_prefs"
        private const val VERSION_KEY = "key_version"
        private const val MASTER_KEY_URI = "android-keystore://sms_forwarder_identity"
    }
}
