package com.viswa2k.smsforwarder.core

/**
 * What the Log "Clear" action may remove. Log rows are also the dedupe keys, so clearing moves the
 * catch-up floor to now (the inbox is never re-forwarded), and only rows received before that floor go:
 * a row received at/after it (arrived while clearing) is a key catch-up still needs, and a row whose send
 * is still queued stays (deleting it would cascade-delete the queued send).
 */
object LogClearPolicy {
    /** One log row and, per outbox row, whether it is still PENDING. */
    data class Row(val logId: Long, val receivedAt: Long, val sendPending: List<Boolean>)

    fun floorAfterClear(now: Long, currentFloor: Long): Long = maxOf(now, currentFloor)

    /** Ids of the rows to delete, in input order. */
    fun toDelete(rows: List<Row>, floor: Long): List<Long> =
        rows.filter { r -> r.receivedAt < floor && r.sendPending.none { it } }.map { it.logId }
}
