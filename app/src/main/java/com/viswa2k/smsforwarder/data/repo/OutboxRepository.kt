package com.viswa2k.smsforwarder.data.repo

import com.viswa2k.smsforwarder.data.db.OutboxDao
import com.viswa2k.smsforwarder.data.db.OutboxEntity

class OutboxRepository(private val dao: OutboxDao) {
    suspend fun insert(row: OutboxEntity): Long = dao.insert(row)
    /** Pending rows whose next attempt is due, oldest first. */
    suspend fun due(now: Long): List<OutboxEntity> = dao.due(now)
    suspend fun markSent(id: Long) = dao.markSent(id)
    suspend fun markRetry(id: Long, nextAt: Long, error: String?) = dao.markRetry(id, nextAt, error)
    suspend fun markFailed(id: Long, error: String?) = dao.markFailed(id, error)
    suspend fun failingSince(destinationId: Long): Long? = dao.failingSince(destinationId)
    /** Puts a FAILED row back in the queue as new; no-op for any other state. */
    suspend fun resend(outboxId: Long) = dao.resend(outboxId, System.currentTimeMillis())
}
