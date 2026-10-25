package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRulesTest {
    @Test fun withDestinationsFirstThreeEnabled() {
        val r = DefaultRules.create(listOf(1L, 2L), SenderCondition.ANYONE)
        assertEquals(listOf("OTPs", "Bank transactions", "Missed calls", "Unknown senders"), r.map { it.name })
        assertEquals(listOf(true, true, true, false), r.map { it.enabled })
        assertEquals(listOf(0, 1, 2, 3), r.map { it.position })
        assertTrue(r.all { it.id == 0L && it.destinationIds == listOf(1L, 2L) && it.style == Style.SMART })
        assertEquals(setOf(MessageType.OTP), r[0].types)
        assertEquals(setOf(MessageType.TRANSACTION, MessageType.BALANCE), r[1].types)
        assertEquals(setOf(MessageType.MISSED_CALL), r[2].types)
        assertTrue(r[3].allMessages)
        assertFalse(r[0].allMessages)
    }

    @Test fun noDestinationsAllDisabled() {
        val r = DefaultRules.create(emptyList(), SenderCondition.ANYONE)
        assertEquals(4, r.size)
        assertTrue(r.none { it.enabled })
    }

    @Test fun senderAppliesToFirstThreeUnknownSendersAlwaysNotInContacts() {
        val r = DefaultRules.create(listOf(1L), SenderCondition.NOT_IN_CONTACTS)
        assertEquals(List(3) { SenderCondition.NOT_IN_CONTACTS }, r.take(3).map { it.sender })
        assertEquals(SenderCondition.NOT_IN_CONTACTS, r[3].sender)
        val any = DefaultRules.create(listOf(1L), SenderCondition.ANYONE)
        assertEquals(SenderCondition.NOT_IN_CONTACTS, any[3].sender)
    }
}
