package com.viswa2k.smsforwarder.data.repo

import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.OtpExpiry
import com.viswa2k.smsforwarder.data.db.OutboxDao
import com.viswa2k.smsforwarder.data.db.OutboxEntity

class OutboxRepository(private val dao: OutboxDao) {
    suspend fun insert(row: OutboxEntity): Long = dao.insert(row)
    /** Pending rows whose next attempt is due, oldest first. */
    suspend fun due(now: Long): List<OutboxEntity> = dao.due(now)
    /** Every PENDING row, due or not, oldest first. */
    suspend fun pending(): List<OutboxEntity> = dao.pending()
    suspend fun deferPending(destinationId: Long, nextAt: Long) = dao.deferPending(destinationId, nextAt)
    /** Due time of the soonest PENDING row, or null when nothing is pending. */
    suspend fun earliestPendingAt(): Long? = dao.earliestPending()
    /** Current copy of a row (null when its log was deleted). */
    suspend fun byId(id: Long): OutboxEntity? = dao.byId(id)
    suspend fun markSent(id: Long) = dao.markSent(id)
    suspend fun markRetry(id: Long, nextAt: Long, error: String?) = dao.markRetry(id, nextAt, error)
    suspend fun markFailed(id: Long, error: String?) = dao.markFailed(id, error)
    suspend fun failingSince(destinationId: Long): Long? = dao.failingSince(destinationId)
    suspend fun lastErrorFor(destinationId: Long): String? = dao.lastErrorFor(destinationId)
    /**
     * Puts a FAILED row back in the queue as new; no-op for any other state, and refused for an OTP that is
     * masked or older than 1 h ([OtpExpiry], ruling 30). Returns true when the row was re-queued.
     */
    suspend fun resend(outboxId: Long): Boolean {
        val now = System.currentTimeMillis()
        val log = dao.logOf(outboxId) ?: return false
        val type = MessageType.fromCode(log.type) ?: MessageType.IMPORTANT // unknown code: not an OTP
        if (!OtpExpiry.mayResend(log.masked, type, log.receivedAt, now)) return false
        dao.resend(outboxId, now)
        return true
    }
}
