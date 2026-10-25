package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinBackoffTest {
    private val t0 = 1_000_000L

    private fun fail(times: Int, s: PinBackoff.State = PinBackoff.State(), at: Long = t0): PinBackoff.State {
        var st = s
        repeat(times) { st = PinBackoff.onFailure(st, at) }
        return st
    }

    @Test fun fourFailures_noLockout() {
        val s = fail(4)
        assertEquals(0L, PinBackoff.remainingMs(s, t0))
    }

    @Test fun fifthFailure_locks30s() {
        val s = fail(5)
        assertEquals(30_000L, PinBackoff.remainingMs(s, t0))
        assertEquals(10_000L, PinBackoff.remainingMs(s, t0 + 20_000))
        assertEquals(0L, PinBackoff.remainingMs(s, t0 + 30_000))
    }

    @Test fun furtherFailures_double_cappedAt5Min() {
        assertEquals(60_000L, PinBackoff.remainingMs(fail(6), t0))
        assertEquals(120_000L, PinBackoff.remainingMs(fail(7), t0))
        assertEquals(240_000L, PinBackoff.remainingMs(fail(8), t0))
        assertEquals(300_000L, PinBackoff.remainingMs(fail(9), t0))
        assertEquals(300_000L, PinBackoff.remainingMs(fail(30), t0))
    }

    @Test fun successResets() {
        assertEquals(PinBackoff.State(), PinBackoff.onSuccess())
    }

    @Test fun encodeDecodeRoundTrip_andGarbageIsFresh() {
        val s = fail(6)
        assertEquals(s, PinBackoff.decode(PinBackoff.encode(s)))
        assertEquals(PinBackoff.State(), PinBackoff.decode("nonsense"))
        assertEquals(PinBackoff.State(), PinBackoff.decode(""))
    }

    @Test fun clockMovedBack_lockoutStillBounded() {
        // A lockout never exceeds the cap even if the clock jumps backwards.
        val s = fail(5)
        assertTrue(PinBackoff.remainingMs(s, t0 - 3_600_000) <= 300_000L)
    }
}
