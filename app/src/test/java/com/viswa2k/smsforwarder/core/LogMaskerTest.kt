package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LogMaskerTest {
    @Test fun masksKnownCode() =
        assertEquals("•••••• is your OTP", LogMasker.mask("482913 is your OTP", "482913"))

    @Test fun masksEveryOccurrence() =
        assertEquals("•••• then ••••", LogMasker.mask("1234 then 1234", "1234"))

    @Test fun nullOtpMasksFourToEightDigitRuns() =
        assertEquals("code •••••• ok 12 and ••••••••", LogMasker.mask("code 482913 ok 12 and 12345678", null))

    @Test fun nullOtpLeavesLongerRunsAlone() =
        assertEquals("123456789", LogMasker.mask("123456789", null))

    @Test fun blankOtpFallsBackToDigitRuns() =
        assertEquals("•••• x", LogMasker.mask("1234 x", ""))
}
