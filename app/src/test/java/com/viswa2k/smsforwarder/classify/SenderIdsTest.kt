// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/SenderIdsTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderIdsTest {

    private data class Case(
        val address: String,
        val type: SenderType,
        val id: String,
        val prefix: String?,
        val suffix: DltSuffix,
        val business: Boolean,
        val service: Boolean,
    )

    @Test fun `header prefix and DLT suffix rules`() {
        val cases = listOf(
            Case("AX-HDFCBK", SenderType.ALPHANUMERIC, "HDFCBK", "AX", DltSuffix.NONE, true, false),
            Case("VM-HDFCBK-S", SenderType.ALPHANUMERIC, "HDFCBK", "VM", DltSuffix.SERVICE, true, false),
            Case("JD-SHOPCO-P", SenderType.ALPHANUMERIC, "SHOPCO", "JD", DltSuffix.PROMOTIONAL, true, false),
            Case("AD-FOODIE-T", SenderType.ALPHANUMERIC, "FOODIE", "AD", DltSuffix.TRANSACTIONAL, true, false),
            Case("CP-ITDEPT-G", SenderType.ALPHANUMERIC, "ITDEPT", "CP", DltSuffix.GOVERNMENT, true, false),
            Case("hdfcbk-s", SenderType.ALPHANUMERIC, "HDFCBK", null, DltSuffix.SERVICE, true, false),
            Case("VMHDFCBK", SenderType.ALPHANUMERIC, "HDFCBK", "VM", DltSuffix.NONE, true, false),
            Case("BX-AMAZN", SenderType.ALPHANUMERIC, "AMAZN", "BX", DltSuffix.NONE, true, false),
            Case("AD-650025", SenderType.ALPHANUMERIC, "650025", "AD", DltSuffix.NONE, true, true),
            Case("AD-650025-P", SenderType.ALPHANUMERIC, "650025", "AD", DltSuffix.PROMOTIONAL, true, true),
            Case("650025", SenderType.SHORT_CODE, "650025", null, DltSuffix.NONE, true, true),
            Case("57575", SenderType.SHORT_CODE, "57575", null, DltSuffix.NONE, true, false),
            Case("12345678", SenderType.SHORT_CODE, "12345678", null, DltSuffix.NONE, true, false),
            Case("+919876543210", SenderType.PHONE_NUMBER, "+919876543210", null, DltSuffix.NONE, false, false),
            Case("9876543210", SenderType.PHONE_NUMBER, "9876543210", null, DltSuffix.NONE, false, false),
            Case("9123456789", SenderType.PHONE_NUMBER, "9123456789", null, DltSuffix.NONE, false, false),
            Case("+1 (650) 555-0100", SenderType.PHONE_NUMBER, "+1 (650) 555-0100", null, DltSuffix.NONE, false, false),
            Case("+91650025", SenderType.SHORT_CODE, "650025", null, DltSuffix.NONE, false, true),
            Case("alerts@rbm.example", SenderType.EMAIL, "alerts@rbm.example", null, DltSuffix.NONE, true, false),
            Case("", SenderType.UNKNOWN, "", null, DltSuffix.NONE, false, false),
        )
        for (c in cases) {
            val s = SenderIds.parse(c.address)
            assertEquals(c.address, c.type, s.type)
            assertEquals(c.address, c.id, s.canonicalId)
            assertEquals(c.address, c.prefix, s.operatorPrefix)
            assertEquals(c.address, c.suffix, s.suffix)
            assertEquals(c.address, c.business, s.isBusiness)
            assertEquals(c.address, c.service, s.isServiceStyle)
        }
    }

    @Test fun `canonical header id`() {
        assertEquals("HDFCBK", SenderIds.canonicalHeader("AX-HDFCBK-S"))
        assertEquals("ICICIB", SenderIds.canonicalHeader("ICICIB"))
        assertEquals("HDFCBK", SenderIds.canonicalHeader("TMHDFCBK"))
    }

    @Test fun `business routing test ML g`() {
        assertTrue(SenderIds.isBusiness("HDFCBK"))
        assertTrue(SenderIds.isBusiness("5676791"))
        assertFalse(SenderIds.isBusiness("987654321"))
        assertFalse(SenderIds.isBusiness("+44 7700 900123"))
        assertFalse(SenderIds.isBusiness(""))
        // comma-joined: business when not starting with + and < 15 non-letter characters
        assertTrue(SenderIds.isBusiness("12345,67890"))
        assertFalse(SenderIds.isBusiness("9876543210,9876501234"))
        assertFalse(SenderIds.isBusiness("+919876543210,+919876501234"))
    }

    @Test fun `indian short numbers`() {
        assertTrue(SenderIds.isIndianShortNumber("+91650025"))
        assertTrue(SenderIds.isIndianShortNumber("9112345678".take(9)))
        assertFalse(SenderIds.isIndianShortNumber("+919876543210"))
        assertFalse(SenderIds.isIndianShortNumber("9123456789")) // bare 10-digit mobile starting with 91
        assertFalse(SenderIds.isIndianShortNumber("91234567890"))
        assertTrue(SenderIds.isIndianShortNumber("91650025"))
        assertFalse(SenderIds.isIndianShortNumber("+4412345678"))
    }

    @Test fun `personal vs unknown vs business`() {
        assertEquals(SenderClass.PERSONAL, SenderIds.parse("+919876543210").senderClass(isKnown = true))
        assertEquals(SenderClass.UNKNOWN, SenderIds.parse("+919876543210").senderClass(isKnown = false))
        assertEquals(SenderClass.BUSINESS, SenderIds.parse("AX-HDFCBK").senderClass(isKnown = true))
        assertEquals(SenderClass.BUSINESS, SenderIds.parse("57575").senderClass(isKnown = false))
    }
}
