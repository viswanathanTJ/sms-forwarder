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

    private val everything = Rule(1, "All", true, true, emptySet(), SenderCondition.ANYONE, listOf(1), Style.SMART, null, 0)
    private val dests = listOf(Destination(1, Channel.TELEGRAM, "Me", "5"))
    private fun decide(origin: Origin, type: MessageType, masterOn: Boolean = true) =
        SourceFilter.decide(origin, type, otp, masterOn, SenderKind.UNKNOWN_NUMBER, "x", listOf(everything), dests)

    @Test fun decideOrder() {
        assertEquals(SkipReason.PAUSED, decide(Origin.WHATSAPP, MessageType.PERSONAL, masterOn = false).skip)
        assertEquals(SkipReason.SPAM, decide(Origin.WHATSAPP, MessageType.SPAM).skip)
        assertEquals(SkipReason.SOURCE_FILTERED, decide(Origin.WHATSAPP, MessageType.PERSONAL).skip)
        assertEquals(null, decide(Origin.WHATSAPP, MessageType.OTP).skip)
        assertEquals(null, decide(Origin.SMS, MessageType.PERSONAL).skip)
        assertEquals(SkipReason.SPAM, decide(Origin.SMS, MessageType.SPAM).skip)
    }

    @Test fun noteListsTypesInCodeOrder() {
        assertEquals("WhatsApp: only OTP forwarded", SourceFilter.note(otp))
        assertEquals("WhatsApp: only OTP, Balance alert forwarded", SourceFilter.note(setOf(MessageType.BALANCE, MessageType.OTP)))
    }

    @Test fun shouldRecordOnlyForwardedWhatsApp() {
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, decide(Origin.WHATSAPP, MessageType.PERSONAL))) // filtered
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, decide(Origin.WHATSAPP, MessageType.SPAM)))
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, decide(Origin.WHATSAPP, MessageType.OTP, masterOn = false)))
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, Decision(emptyList(), emptyList(), SkipReason.NO_RULE)))
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, Decision(emptyList(), emptyList(), SkipReason.CONTACTS_PERMISSION_MISSING)))
        assertEquals(false, SourceFilter.shouldRecord(Origin.WHATSAPP, Decision(listOf(1L), emptyList(), null)))
        assertEquals(true, SourceFilter.shouldRecord(Origin.WHATSAPP, decide(Origin.WHATSAPP, MessageType.OTP)))
        assertEquals(true, SourceFilter.shouldRecord(Origin.SMS, decide(Origin.SMS, MessageType.SPAM)))
        assertEquals(true, SourceFilter.shouldRecord(Origin.SMS, Decision(emptyList(), emptyList(), SkipReason.NO_RULE)))
    }
}
