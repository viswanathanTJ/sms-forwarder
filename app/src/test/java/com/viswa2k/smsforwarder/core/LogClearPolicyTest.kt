package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LogClearPolicyTest {
    @Test fun floorMovesToNowSoCatchUpCannotReForwardTheInbox() {
        assertEquals(5_000L, LogClearPolicy.floorAfterClear(now = 5_000L, currentFloor = 1_000L))
    }

    @Test fun floorNeverMovesBack() {
        assertEquals(9_000L, LogClearPolicy.floorAfterClear(now = 5_000L, currentFloor = 9_000L))
    }

    @Test fun rowsWithAQueuedSendSurvive_othersBeforeTheFloorAreDeleted() {
        val rows = listOf(
            LogClearPolicy.Row(1, receivedAt = 100, sendPending = emptyList()),
            LogClearPolicy.Row(2, receivedAt = 100, sendPending = listOf(false, false)),
            LogClearPolicy.Row(3, receivedAt = 100, sendPending = listOf(false, true)),
            LogClearPolicy.Row(4, receivedAt = 100, sendPending = listOf(true)),
        )
        assertEquals(listOf(1L, 2L), LogClearPolicy.toDelete(rows, floor = 500))
    }

    // A row received at/after the new floor (arrived while clearing) is a dedupe key catch-up still needs.
    @Test fun rowsAtOrAfterTheFloorSurvive() {
        val rows = listOf(
            LogClearPolicy.Row(1, receivedAt = 499, sendPending = listOf(false)),
            LogClearPolicy.Row(2, receivedAt = 500, sendPending = listOf(false)),
            LogClearPolicy.Row(3, receivedAt = 900, sendPending = emptyList()),
        )
        assertEquals(listOf(1L), LogClearPolicy.toDelete(rows, floor = 500))
    }
}
