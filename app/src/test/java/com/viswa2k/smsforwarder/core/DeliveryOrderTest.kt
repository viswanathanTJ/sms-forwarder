package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.core.DeliveryOrder.Row
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryOrderTest {
    private val now = 10_000L

    /** Runs a pass; [outcome] returns null for Sent or the retry time. Returns the attempted ids. */
    private fun run(order: DeliveryOrder, outcome: (Row) -> Long?): List<Long> {
        val attempted = mutableListOf<Long>()
        while (true) {
            val row = order.next() ?: break
            attempted += row.id
            val retryAt = outcome(row)
            if (retryAt == null) order.onSent(row) else order.onRetry(row, retryAt)
        }
        return attempted
    }

    @Test fun dueRowsGoOldestFirst() {
        val order = DeliveryOrder(
            listOf(Row(2, 1, 200, 0), Row(1, 1, 100, 0), Row(3, 2, 150, 0)), now,
        )
        assertEquals(listOf(1L, 3L, 2L), run(order) { null })
    }

    @Test fun newerRowDoesNotOvertakeOlderOneOnBackoff() {
        val order = DeliveryOrder(listOf(Row(1, 1, 100, now + 60_000), Row(2, 1, 200, now - 1)), now)
        assertEquals(emptyList<Long>(), run(order) { null })
        assertEquals(mapOf(1L to now + 60_000), order.deferrals())
    }

    @Test fun retryBlocksRestOfDestinationButNotOthers() {
        val order = DeliveryOrder(
            listOf(Row(1, 1, 100, 0), Row(2, 1, 200, 0), Row(3, 2, 300, 0)), now,
        )
        val attempted = run(order) { if (it.id == 1L) now + 30_000 else null }
        assertEquals(listOf(1L, 3L), attempted)
        assertEquals(mapOf(1L to now + 30_000), order.deferrals())
    }

    @Test fun sentUnlocksNotYetDueRowsOfSameDestination() {
        val order = DeliveryOrder(listOf(Row(1, 1, 100, 0), Row(2, 1, 200, now + 3_600_000)), now)
        assertEquals(listOf(1L, 2L), run(order) { null })
        assertEquals(emptyMap<Long, Long>(), order.deferrals())
    }

    @Test fun retryAfterUnlockedSendStillBlocksTheRest() {
        val order = DeliveryOrder(
            listOf(Row(1, 1, 100, 0), Row(2, 1, 200, now + 5_000), Row(3, 1, 300, now + 5_000)), now,
        )
        assertEquals(listOf(1L, 2L), run(order) { if (it.id == 2L) now + 60_000 else null })
    }

    @Test fun nothingPendingMeansNothingToDo() {
        assertEquals(emptyList<Long>(), run(DeliveryOrder(emptyList(), now)) { null })
    }
}
