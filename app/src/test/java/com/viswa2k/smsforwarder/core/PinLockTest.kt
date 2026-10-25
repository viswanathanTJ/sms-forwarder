package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PinLockTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun validity_4to6Digits() {
        assertTrue(PinLock.isValid("1234"))
        assertTrue(PinLock.isValid("123456"))
        assertFalse(PinLock.isValid("123"))
        assertFalse(PinLock.isValid("1234567"))
        assertFalse(PinLock.isValid("12a4"))
        assertFalse(PinLock.isValid(" 1234"))
        assertFalse(PinLock.isValid("١٢٣٤")) // non-ASCII digits
    }

    @Test fun recordVerifies() {
        val stored = PinLock.record("4821", salt)
        assertTrue(PinLock.verify("4821", stored))
        assertFalse(PinLock.verify("4822", stored))
        assertFalse(PinLock.verify("", stored))
    }

    @Test fun recordIsSaltPlusSha256_notThePin() {
        val stored = PinLock.record("4821", salt)
        val (s, h) = stored.split(":")
        assertEquals(32, s.length) // 16 bytes hex
        assertEquals(64, h.length) // SHA-256 hex
        assertFalse(stored.contains("4821"))
    }

    @Test fun differentSalt_differentRecord() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertNotEquals(PinLock.record("4821", salt), PinLock.record("4821", other))
    }

    @Test fun randomSaltRecord_verifies() {
        val stored = PinLock.newRecord("909090", Random(7))
        assertTrue(PinLock.verify("909090", stored))
    }

    @Test fun malformedRecord_neverVerifies() {
        assertFalse(PinLock.verify("1234", "garbage"))
        assertFalse(PinLock.verify("1234", "zz:zz"))
        assertFalse(PinLock.verify("1234", ""))
    }

    @Test fun invalidPin_cannotBeRecorded() {
        assertThrows(IllegalArgumentException::class.java) { PinLock.record("12", salt) }
    }
}
