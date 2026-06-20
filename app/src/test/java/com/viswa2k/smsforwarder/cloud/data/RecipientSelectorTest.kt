package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSelectorTest {
    private fun g(reader: String, source: String) = AccessGrant(reader, source)

    @Test
    fun includesAdminPlusGrantedReaders_forThatSourceOnly() {
        val matrix = listOf(g("B", "A"), g("C", "A"), g("B", "X"))
        assertEquals(setOf("B", "C", "ADMIN"), RecipientSelector.recipientDeviceIds("A", matrix, setOf("ADMIN")))
    }

    @Test
    fun adminAlwaysIncluded_evenWithNoGrants() {
        assertEquals(setOf("ADMIN"), RecipientSelector.recipientDeviceIds("A", emptyList(), setOf("ADMIN")))
    }
}
