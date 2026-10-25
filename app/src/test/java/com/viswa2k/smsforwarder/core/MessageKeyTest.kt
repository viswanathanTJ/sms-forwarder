package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
