package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the Ints written to the database; changing any of these breaks stored rows. */
class StoredCodesTest {
    @Test fun skipReasonCodes() {
        assertEquals(listOf(1, 2, 3, 4, 5), SkipReason.entries.map { it.code })
        assertEquals(SkipReason.NO_RULE, SkipReason.fromCode(3))
        assertEquals(SkipReason.SOURCE_FILTERED, SkipReason.fromCode(5))
        assertEquals(null, SkipReason.fromCode(0))
    }

    @Test fun senderKindCodes() {
        assertEquals(
            mapOf(SenderKind.BUSINESS to 0, SenderKind.CONTACT to 1, SenderKind.UNKNOWN_NUMBER to 2, SenderKind.UNVERIFIED to 3),
            SenderKind.entries.associateWith { it.code },
        )
        assertEquals(SenderKind.UNVERIFIED, SenderKind.fromCode(3))
    }
}
