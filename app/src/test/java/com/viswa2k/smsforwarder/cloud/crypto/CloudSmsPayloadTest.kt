package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSmsPayloadTest {
    @Test
    fun jsonRoundTrips() {
        val p = CloudSmsPayload("+100", "héllo, world", 123L, "Pixel")
        assertEquals(p, CloudSmsPayload.fromJsonBytes(p.toJsonBytes()))
    }
}
