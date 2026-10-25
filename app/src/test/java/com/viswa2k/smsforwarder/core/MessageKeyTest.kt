package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageKeyTest {
    @Test fun `same SMS seen live and in the inbox has one key`() {
        assertEquals(
            MessageKey.of("+919840012345", 1_000_500, " hi "),
            MessageKey.of("09840012345", 1_000_900, "hi"),
        )
    }

    @Test fun `business senders compare case-insensitively`() {
        assertEquals(MessageKey.of("vm-hdfcbk", 5_000, "x"), MessageKey.of("VM-HDFCBK", 5_000, "x"))
    }

    @Test fun `different body or second gives a different key`() {
        assertNotEquals(MessageKey.of("A", 1_000, "x"), MessageKey.of("A", 1_000, "y"))
        assertNotEquals(MessageKey.of("A", 1_000, "x"), MessageKey.of("A", 2_000, "x"))
    }

    @Test fun `loose key ignores time and formatting of the sender`() {
        assertEquals(MessageKey.loose("+919840012345", " hi "), MessageKey.loose("09840012345", "hi"))
    }

    @Test fun `loose duplicate needs a logged row within two minutes`() {
        assertTrue(MessageKey.isLooseDuplicate(listOf(1_000_000L), 1_000_000L + 120_000))
        assertTrue(MessageKey.isLooseDuplicate(listOf(1_000_000L), 1_000_000L - 120_000))
        assertFalse(MessageKey.isLooseDuplicate(listOf(1_000_000L), 1_000_000L + 120_001))
        assertFalse(MessageKey.isLooseDuplicate(emptyList(), 5L))
    }

    @Test fun `whatsapp chats compare by trimmed lower-cased title, digits kept`() {
        assertEquals(MessageKey.of("WA:Amazon", 5_000, "x"), MessageKey.of("WA: amazon ", 5_000, "x"))
        assertTrue(MessageKey.of("WA:+91 98400 12345", 5_000, "x").startsWith("wa:+91 98400 12345|"))
        assertNotEquals(MessageKey.of("WA:Amazon", 5_000, "x"), MessageKey.of("AMAZON", 5_000, "x"))
        assertEquals(MessageKey.loose("WA:Amazon", "x"), MessageKey.loose("wa:AMAZON", "x"))
    }
}
