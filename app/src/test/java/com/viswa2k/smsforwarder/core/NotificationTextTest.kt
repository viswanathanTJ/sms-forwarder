package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun `housekeeping texts are recognised`() {
        listOf(
            "3 new messages", "1 new message", "Checking for new messages", "5 messages from 2 chats",
            "2 messages from 1 chat", "Backup in progress", "Backing up messages", "Restoring media",
            "Incoming voice call", "Ongoing video call", "Missed voice call", "2 missed calls", "Calling…", "Ringing…",
            "  4 NEW MESSAGES  ",
        ).forEach { assertTrue(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `real messages are not housekeeping`() {
        listOf(
            "482913 is your Amazon OTP", "I have 3 new messages for you tomorrow", "Call me when free",
            "Rs 500 debited from A/c XX1234", "",
        ).forEach { assertFalse(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `sender address for chats and groups`() {
        assertEquals("WA:Amazon", NotificationText.senderAddress(" Amazon ", null))
        assertEquals("WA:Amazon", NotificationText.senderAddress("Amazon", "Amazon"))
        assertEquals("WA:Family · Mom", NotificationText.senderAddress("Family", "Mom"))
        assertEquals("WA:Family", NotificationText.senderAddress("Family", " "))
    }

    @Test fun `display name and chat of an address`() {
        assertEquals("WhatsApp · Amazon", NotificationText.displayName("WA:Amazon"))
        assertEquals("WhatsApp · Family · Mom", NotificationText.displayName("WA:Family · Mom"))
        assertEquals("VM-HDFCBK", NotificationText.displayName("VM-HDFCBK"))
        assertEquals("Family", NotificationText.chatOf("WA:Family · Mom"))
        assertEquals("Amazon", NotificationText.chatOf("WA:Amazon"))
    }

    @Test fun `whatsapp address detection`() {
        assertTrue(NotificationText.isWhatsAppAddress("WA:Amazon"))
        assertFalse(NotificationText.isWhatsAppAddress("VM-HDFCBK"))
        assertFalse(NotificationText.isWhatsAppAddress("WAYBIL"))
        assertFalse(NotificationText.isWhatsAppAddress("+919840012345"))
    }

    @Test fun `timestamps older than ten minutes before the post are rejected`() {
        val post = 10_000_000L
        assertTrue(NotificationText.acceptTimestamp(post, post, 0))
        assertTrue(NotificationText.acceptTimestamp(post - 600_000, post, 0))
        assertFalse(NotificationText.acceptTimestamp(post - 600_001, post, 0))
        assertTrue(NotificationText.acceptTimestamp(post + 5_000, post, 0))
    }

    @Test fun `timestamps before the toggle was switched on are rejected`() {
        val post = 10_000_000L
        assertFalse(NotificationText.acceptTimestamp(post - 1_000, post, waFloor = post - 500))
        assertTrue(NotificationText.acceptTimestamp(post - 500, post, waFloor = post - 500))
    }

    @Test fun `accept combines text, housekeeping and time rules`() {
        val post = 10_000_000L
        fun m(text: String, ts: Long = post) = WaMessage("Amazon", null, text, ts)
        assertTrue(NotificationText.accept(m("482913 is your OTP"), post, 0))
        assertFalse(NotificationText.accept(m("  "), post, 0))
        assertFalse(NotificationText.accept(m("3 new messages"), post, 0))
        assertFalse(NotificationText.accept(m("482913 is your OTP", post - 3_600_000), post, 0))
    }

    @Test fun `only whatsapp packages are allowed`() {
        assertTrue(NotificationText.isAllowedPackage("com.whatsapp"))
        assertTrue(NotificationText.isAllowedPackage("com.whatsapp.w4b"))
        assertFalse(NotificationText.isAllowedPackage("com.google.android.apps.messaging"))
        assertFalse(NotificationText.isAllowedPackage(null))
    }

    @Test fun `origin codes are stable`() {
        assertEquals(0, Origin.SMS.code)
        assertEquals(1, Origin.WHATSAPP.code)
        assertEquals(Origin.WHATSAPP, Origin.fromCode(1))
        assertEquals(Origin.SMS, Origin.fromCode(7))
    }

    @Test fun `titles lose the unread counter and bidi marks`() {
        assertEquals("Family", NotificationText.cleanTitle("Family (5 messages)"))
        assertEquals("Ravi", NotificationText.cleanTitle("\u200ERavi\u200F (2 new messages)"))
        assertEquals("Amazon (India)", NotificationText.cleanTitle("Amazon (India)"))
        assertEquals("WA:Family · Mom", NotificationText.senderAddress("Family (3 messages)", "Mom"))
    }
}
