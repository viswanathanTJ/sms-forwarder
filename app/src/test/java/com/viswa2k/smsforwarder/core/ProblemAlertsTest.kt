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
