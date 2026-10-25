package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpExpiryTest {
    private val hour = 3_600_000L
    private val now = 10 * hour

    @Test fun otpOlderThanOneHourIsExpired() {
        assertTrue(OtpExpiry.isExpired(MessageType.OTP, receivedAt = now - hour - 1, now = now))
        assertFalse(OtpExpiry.isExpired(MessageType.OTP, receivedAt = now - hour, now = now))
        assertFalse(OtpExpiry.isExpired(MessageType.OTP, receivedAt = now - 59 * 60_000L, now = now))
    }

    @Test fun otherTypesNeverExpire() {
        assertFalse(OtpExpiry.isExpired(MessageType.TRANSACTION, receivedAt = 0, now = now))
    }

    @Test fun maskCutoffMatchesExpiry() {
        assertEquals(now - hour, OtpExpiry.maskBefore(now))
    }

    // Ruling 30: masking fails the still-queued sends instead of sending the masked text.
    @Test fun onlyPendingSendsAreFailedWhenMasking() {
        val sends = listOf(
            OtpExpiry.Send(1, pending = true),
            OtpExpiry.Send(2, pending = false),
            OtpExpiry.Send(3, pending = true),
        )
        assertEquals(listOf(1L, 3L), OtpExpiry.sendsToFail(sends))
        assertEquals("OTP expired (not sent within 1 hour)", OtpExpiry.EXPIRED_ERROR)
    }

    @Test fun resendRefusedForMaskedOrExpiredOtp() {
        assertFalse(OtpExpiry.mayResend(masked = true, type = MessageType.OTP, receivedAt = now, now = now))
        // 61 min old but the prune has not masked it yet: still refused (resend-at-59 / mask-at-60 race).
        assertFalse(OtpExpiry.mayResend(masked = false, type = MessageType.OTP, receivedAt = now - hour - 60_000, now = now))
        assertTrue(OtpExpiry.mayResend(masked = false, type = MessageType.OTP, receivedAt = now - 30 * 60_000L, now = now))
        assertTrue(OtpExpiry.mayResend(masked = false, type = MessageType.TRANSACTION, receivedAt = 0, now = now))
    }
}
