package com.viswa2k.smsforwarder.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoManagerInstrumentedTest {
    @Test
    fun keystoreSealedKeyset_sealsAndOpensDek() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = CryptoManager(ctx)
        cm.ensureIdentityKey()
        val pub = cm.publicKeyset()
        assertTrue(pub.isNotEmpty())
        val dek = cm.newDek()
        assertArrayEquals(dek, cm.openWrappedDek(cm.sealDekTo(pub, dek)))
        val body = cm.encryptBody(dek, "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), cm.decryptBody(dek, body.ciphertext, body.nonce))
    }
}
