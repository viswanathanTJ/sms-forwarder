package com.viswa2k.smsforwarder.core

/**
 * What the Log "Clear" action may remove. Log rows are also the dedupe keys, so clearing moves the
 * catch-up floor to now (the inbox is never re-forwarded), and a row whose send is still queued stays
 * (deleting it would cascade-delete the queued send).
 */
object LogClearPolicy {
    /** One log row and, per outbox row, whether it is still PENDING. */
    data class Row(val logId: Long, val sendPending: List<Boolean>)

    fun floorAfterClear(now: Long, currentFloor: Long): Long = maxOf(now, currentFloor)

    /** Ids of the rows to delete, in input order. */
    fun toDelete(rows: List<Row>): List<Long> = rows.filter { r -> r.sendPending.none { it } }.map { it.logId }
}
