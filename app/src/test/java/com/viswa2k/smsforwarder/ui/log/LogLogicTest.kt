package com.viswa2k.smsforwarder.ui.log

import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.data.db.LogEntity
import com.viswa2k.smsforwarder.data.db.LogWithSends
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.ui.common.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLogicTest {
    private val P = OutboxEntity.STATE_PENDING
    private val S = OutboxEntity.STATE_SENT
    private val F = OutboxEntity.STATE_FAILED

    @Test fun skipWinsOverEverything() {
        assertEquals(DeliveryState.SKIPPED, deliveryState(SkipReason.SPAM, emptyList()))
        assertEquals(DeliveryState.SKIPPED, deliveryState(SkipReason.PAUSED, listOf(S)))
    }

    @Test fun matchedButNothingSentIsSkipped() {
        assertEquals(DeliveryState.SKIPPED, deliveryState(null, emptyList()))
    }

    @Test fun pendingBeatsFailedBeatsSent() {
        assertEquals(DeliveryState.RETRYING, deliveryState(null, listOf(S, F, P)))
        assertEquals(DeliveryState.FAILED, deliveryState(null, listOf(S, F)))
        assertEquals(DeliveryState.FORWARDED, deliveryState(null, listOf(S, S)))
    }

    @Test fun filtersSelectTheirStates() {
        val all = DeliveryState.entries
        assertEquals(all, all.filter { LogFilter.ALL.matches(it) })
        assertEquals(listOf(DeliveryState.FORWARDED), all.filter { LogFilter.FORWARDED.matches(it) })
        assertEquals(listOf(DeliveryState.SKIPPED), all.filter { LogFilter.SKIPPED.matches(it) })
        assertEquals(
            listOf(DeliveryState.RETRYING, DeliveryState.FAILED),
            all.filter { LogFilter.FAILED_RETRYING.matches(it) },
        )
    }

    @Test fun noteForSkipsKeepsTheReason() {
        for (reason in SkipReason.entries) {
            assertEquals(NoteLine.Skipped(reason), noteLine(reason, listOf("OTPs"), listOf("Me")))
        }
    }

    @Test fun noteListsRulesAndDestinations() {
        assertEquals(NoteLine.Routed(listOf("OTPs"), listOf("Me", "Family group")), noteLine(null, listOf("OTPs"), listOf("Me", "Family group")))
    }

    @Test fun noteWithoutDestinationsIsMatchedNothing() {
        assertEquals(NoteLine.MatchedNothingSent, noteLine(null, listOf("OTPs"), emptyList()))
    }

    @Test fun routedTextJoinsWithArrow() {
        assertEquals("OTPs → Me, Family group", NoteLine.Routed(listOf("OTPs"), listOf("Me", "Family group")).text())
        assertEquals("→ Me", NoteLine.Routed(emptyList(), listOf("Me")).text())
    }

    private fun entry(skip: Int? = null, masked: Boolean = false, body: String = "hello", rules: String = "") = LogEntity(
        id = 7, key = "k", sender = "AD-AMZN", body = body, receivedAt = 1000, type = MessageType.OTP.code,
        senderKind = 0, extracted = "Code ******", matchedRules = rules, skip = skip, simSlot = null, masked = masked,
    )

    private fun send(id: Long, dest: Long, state: Int, error: String? = null) = OutboxEntity(
        id = id, logId = 7, destinationId = dest, channel = 0, text = "t", state = state,
        firstAttemptAt = 0, nextAttemptAt = 0, lastError = error,
    )

    @Test fun mapsRowWithSendsAndRuleNames() {
        val row = LogWithSends(entry(rules = "1,2,99"), listOf(send(1, 10, S), send(2, 11, F, "chat not found"), send(3, 10, S)))
            .toRowData(mapOf(10L to "Me", 11L to "Family"), mapOf(1L to "OTPs", 2L to "Banks"))
        assertEquals(DeliveryState.FAILED, row.state)
        assertEquals(listOf("OTPs", "Banks"), row.ruleNames)
        assertEquals(listOf("Me", "Family"), row.destinationLabels)
        assertEquals(listOf("Me", "Family", "Me"), row.sends.map { it.destination })
        assertEquals("chat not found", row.sends[1].error)
        assertEquals(MessageType.OTP, row.type)
        assertNull(row.skip)
    }

    @Test fun maskedBodyIsPassedThroughAsStored() {
        val row = LogWithSends(entry(masked = true, body = "****** is your code"), emptyList()).toRowData(emptyMap(), emptyMap())
        assertTrue(row.masked)
        assertEquals("****** is your code", row.body)
        assertFalse(row.body.contains("482913"))
    }
}
