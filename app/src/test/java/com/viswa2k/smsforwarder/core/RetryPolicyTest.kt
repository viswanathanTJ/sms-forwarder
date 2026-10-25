package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RetryPolicyTest {
    private val first = 1_000_000L
    private val h24 = 24 * 3_600_000L

    @Test fun attemptZeroWaitsThirtySeconds() {
        assertEquals(first + 30_000, RetryPolicy.nextAttemptAt(0, first, first))
    }

    @Test fun backoffDoubles() {
        assertEquals(first + 60_000, RetryPolicy.nextAttemptAt(1, first, first))
        assertEquals(first + 120_000, RetryPolicy.nextAttemptAt(2, first, first))
    }

    @Test fun attemptTenIsCappedAtOneHour() {
        assertEquals(first + 3_600_000, RetryPolicy.nextAttemptAt(10, first, first))
        assertEquals(first + 3_600_000, RetryPolicy.nextAttemptAt(500, first, first))
    }

    @Test fun retryAfterWins() {
        assertEquals(first + 7_000, RetryPolicy.nextAttemptAt(5, first, first, retryAfterMs = 7_000))
    }

    @Test fun givesUpTwentyFourHoursAfterFirstAttempt() {
        assertNull(RetryPolicy.nextAttemptAt(3, first, first + h24))
        assertNull(RetryPolicy.nextAttemptAt(3, first, first + h24 + 1))
        assertNotNull(RetryPolicy.nextAttemptAt(3, first, first + h24 - 1))
    }
}
