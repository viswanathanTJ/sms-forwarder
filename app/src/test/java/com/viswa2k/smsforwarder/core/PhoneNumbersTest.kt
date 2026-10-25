package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test fun normalizeKeepsLastTenDigits() {
        assertEquals("9840012345", PhoneNumbers.normalize("+91 98400 12345"))
        assertEquals("9840012345", PhoneNumbers.normalize("09840012345"))
        assertEquals("9840012345", PhoneNumbers.normalize("9840012345"))
    }

    @Test fun shortNumbersKeepAllDigits() {
        assertEquals("12345", PhoneNumbers.normalize("12-345"))
    }

    @Test fun alphanumericSenderIsUppercasedUnchanged() {
        assertEquals("VM-HDFCBK", PhoneNumbers.normalize("vm-hdfcbk"))
    }

    @Test fun sameAcrossFormats() {
        assertTrue(PhoneNumbers.same("+919840012345", "09840012345"))
        assertFalse(PhoneNumbers.same("+919840012345", "9840012346"))
    }

    @Test fun alphanumericIdsAreNotSameAsPartial() {
        assertFalse(PhoneNumbers.same("VM-HDFCBK", "HDFCBK"))
        assertTrue(PhoneNumbers.same("VM-HDFCBK", "vm-hdfcbk"))
    }

    @Test fun blankNeverMatches() {
        assertFalse(PhoneNumbers.same("", ""))
    }
}
