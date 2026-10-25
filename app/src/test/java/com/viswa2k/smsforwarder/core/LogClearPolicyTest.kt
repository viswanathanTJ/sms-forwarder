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

    @Test fun rowsWithAQueuedSendSurvive_othersAreDeleted() {
        val rows = listOf(
            LogClearPolicy.Row(1, sendPending = emptyList()),
            LogClearPolicy.Row(2, sendPending = listOf(false, false)),
            LogClearPolicy.Row(3, sendPending = listOf(false, true)),
            LogClearPolicy.Row(4, sendPending = listOf(true)),
        )
        assertEquals(listOf(1L, 2L), LogClearPolicy.toDelete(rows))
    }
}
