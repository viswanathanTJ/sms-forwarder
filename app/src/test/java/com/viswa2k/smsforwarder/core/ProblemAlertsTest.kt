package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemAlertsTest {
    private val h = 3_600_000L

    @Test fun newProblemIsRaisedAndRecorded() {
        val plan = ProblemAlerts.plan(emptyMap(), setOf("sms"), now = 1000)
        assertEquals(listOf("sms"), plan.raise)
        assertTrue(plan.resolve.isEmpty())
        assertEquals(mapOf("sms" to 1000L), plan.newState)
    }

    @Test fun sameProblemTwoHoursLaterIsNotRaisedAgain() {
        val plan = ProblemAlerts.plan(mapOf("sms" to 0L), setOf("sms"), now = 2 * h)
        assertTrue(plan.raise.isEmpty())
        assertTrue(plan.resolve.isEmpty())
        assertEquals(mapOf("sms" to 0L), plan.newState)
    }

    @Test fun sameProblemAfter25HoursIsRaisedAgain() {
        val plan = ProblemAlerts.plan(mapOf("sms" to 0L), setOf("sms"), now = 25 * h)
        assertEquals(listOf("sms"), plan.raise)
        assertEquals(mapOf("sms" to 25 * h), plan.newState)
    }

    @Test fun exactly24HoursRaisesAgain() {
        assertEquals(listOf("sms"), ProblemAlerts.plan(mapOf("sms" to 0L), setOf("sms"), now = 24 * h).raise)
    }

    @Test fun goneProblemIsResolvedAndRemoved() {
        val plan = ProblemAlerts.plan(mapOf("sms" to 0L, "battery" to 5L), setOf("battery"), now = 10)
        assertEquals(listOf("sms"), plan.resolve)
        assertTrue(plan.raise.isEmpty())
        assertEquals(mapOf("battery" to 5L), plan.newState)
    }
}

class ProblemAlertsApplyTest {
    @Test fun raiseSentRecordsNow() =
        assertEquals(mapOf("a" to 9L), ProblemAlerts.afterRaise(emptyMap(), "a", 9, sent = true))

    @Test fun raiseFailedNewKeyStaysAbsent() =
        assertEquals(emptyMap<String, Long>(), ProblemAlerts.afterRaise(emptyMap(), "a", 9, sent = false))

    @Test fun raiseFailedExistingKeepsOldTimestamp() =
        assertEquals(mapOf("a" to 1L), ProblemAlerts.afterRaise(mapOf("a" to 1L), "a", 99, sent = false))

    @Test fun resolveSentRemoves() =
        assertEquals(emptyMap<String, Long>(), ProblemAlerts.afterResolve(mapOf("a" to 1L), "a", sent = true))

    @Test fun resolveFailedKeepsKeyForRetry() =
        assertEquals(mapOf("a" to 1L), ProblemAlerts.afterResolve(mapOf("a" to 1L), "a", sent = false))

    @Test fun mixedRaiseAndResolveWithOneFailure() {
        val state = mapOf("old" to 0L, "stuck" to 5L)
        val plan = ProblemAlerts.plan(state, setOf("stuck", "fresh"), now = 100)
        assertEquals(listOf("fresh"), plan.raise)
        assertEquals(listOf("old"), plan.resolve)
        var s = ProblemAlerts.afterRaise(state, "fresh", 100, sent = false)
        s = ProblemAlerts.afterResolve(s, "old", sent = true)
        assertEquals(mapOf("stuck" to 5L), s)
    }

    @Test fun `a problem whose feature was switched off is dropped silently`() {
        val state = mapOf("notif_access" to 5L, "sms" to 7L)
        assertEquals(mapOf("sms" to 7L), ProblemAlerts.dropInactive(state, "notif_access", active = false))
        assertEquals(state, ProblemAlerts.dropInactive(state, "notif_access", active = true))
    }

    @Test fun `notification access text covers never granted`() =
        assertEquals("WhatsApp notification access is not allowed", Problem.NOTIF_ACCESS.text)

    @Test fun `retired notif key is dropped silently, other keys kept`() {
        val state = mapOf("notif" to 1L, "sms" to 2L)
        assertEquals(mapOf("sms" to 2L), ProblemAlerts.dropRetired(state))
    }

    @Test fun `stable keys of remaining problems`() =
        assertEquals(
            listOf("sms", "contacts", "battery", "classifier", "paused", "notif_access"),
            Problem.entries.map { it.key },
        )
}
