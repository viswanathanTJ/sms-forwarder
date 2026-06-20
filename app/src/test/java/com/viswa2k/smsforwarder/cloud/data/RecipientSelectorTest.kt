package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSelectorTest {
    private fun g(reader: String, source: String) = AccessGrant(reader, source)

    @Test
    fun includesSelfAdminAndGrantedReaders_forThatSourceOnly() {
        val matrix = listOf(g("B", "A"), g("C", "A"), g("B", "X"))
        // B and C granted access to A, ADMIN always, and A itself (self).
        assertEquals(setOf("A", "B", "C", "ADMIN"), RecipientSelector.recipientDeviceIds("A", matrix, setOf("ADMIN")))
    }

    @Test
    fun selfAndAdminIncluded_evenWithNoGrants() {
        assertEquals(setOf("A", "ADMIN"), RecipientSelector.recipientDeviceIds("A", emptyList(), setOf("ADMIN")))
    }
}
