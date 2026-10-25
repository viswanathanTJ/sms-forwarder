package com.viswa2k.smsforwarder.core

/**
 * Decides which pending outbox rows to send in one pass so each destination is served strictly in
 * `firstAttemptAt` order (Review Focus 4): an older row on backoff is never overtaken by a newer one.
 *
 * Per destination: a row is attempted when it is due, or when an earlier row of that destination was
 * just Sent in this pass. A Retry (or a head row that is not yet due) blocks the destination for the
 * rest of the pass; its remaining rows must be pushed back to that time ([deferrals]).
 */
class DeliveryOrder(rows: List<Row>, private val now: Long) {
    data class Row(val id: Long, val destinationId: Long, val firstAttemptAt: Long, val nextAttemptAt: Long)

    private val queue = rows.sortedWith(compareBy({ it.firstAttemptAt }, { it.id })).iterator()
    private val unlocked = HashSet<Long>()
    private val blocked = LinkedHashMap<Long, Long>()

    /** The next row to attempt, or null when the pass is over. Report its outcome before asking again. */
    fun next(): Row? {
        while (queue.hasNext()) {
            val row = queue.next()
            val dest = row.destinationId
            if (dest in blocked) continue
            if (dest in unlocked || row.nextAttemptAt <= now) return row
            blocked[dest] = row.nextAttemptAt
        }
        return null
    }

    /** The row was delivered: this destination's other pending rows become due now. */
    fun onSent(row: Row) { unlocked += row.destinationId }

    /** The row will be retried at [nextAt]: stop serving this destination in this pass. */
    fun onRetry(row: Row, nextAt: Long) { blocked[row.destinationId] = nextAt }

    /** destinationId to the time its still-pending rows (that are due earlier) must be pushed back to. */
    fun deferrals(): Map<Long, Long> = blocked
}
