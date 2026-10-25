package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFilterTest {
    private val otp = setOf(MessageType.OTP)

    @Test fun smsIsAlwaysAllowed() {
        for (t in MessageType.entries) assertTrue(SourceFilter.allows(Origin.SMS, t, emptySet()))
    }

    @Test fun whatsAppOnlyAllowsSelectedTypes() {
        assertTrue(SourceFilter.allows(Origin.WHATSAPP, MessageType.OTP, otp))
        assertFalse(SourceFilter.allows(Origin.WHATSAPP, MessageType.PERSONAL, otp))
        assertFalse(SourceFilter.allows(Origin.WHATSAPP, MessageType.TRANSACTION, otp))
        assertTrue(SourceFilter.allows(Origin.WHATSAPP, MessageType.TRANSACTION, otp + MessageType.TRANSACTION))
    }

    @Test fun spamIsNeverAllowed() {
        assertFalse(SourceFilter.allows(Origin.WHATSAPP, MessageType.SPAM, MessageType.entries.toSet()))
    }

    @Test fun defaultIsOtpOnlyAndSpamNotSelectable() {
        assertEquals(otp, SourceFilter.DEFAULT_WHATSAPP_TYPES)
        assertEquals(8, SourceFilter.SELECTABLE.size)
        assertFalse(MessageType.SPAM in SourceFilter.SELECTABLE)
    }

    @Test fun fromCodesFallsBackToDefault() {
        assertEquals(otp, SourceFilter.fromCodes(null))
        assertEquals(otp, SourceFilter.fromCodes(emptySet()))
        assertEquals(otp, SourceFilter.fromCodes(listOf("9", "x", "99")))
        assertEquals(setOf(MessageType.BILL, MessageType.OTP), SourceFilter.fromCodes(listOf("5", "2", "9")))
    }

    @Test fun noteListsTypesInCodeOrder() {
        assertEquals("WhatsApp: only OTP forwarded", SourceFilter.note(otp))
        assertEquals("WhatsApp: only OTP, Balance alert forwarded", SourceFilter.note(setOf(MessageType.BALANCE, MessageType.OTP)))
    }
}
