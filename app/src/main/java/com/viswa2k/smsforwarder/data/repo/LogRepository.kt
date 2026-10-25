package com.viswa2k.smsforwarder.data.repo

import com.viswa2k.smsforwarder.core.LogClearPolicy
import com.viswa2k.smsforwarder.core.OtpExpiry
import com.viswa2k.smsforwarder.data.db.LogDao
import com.viswa2k.smsforwarder.data.db.LogEntity
import com.viswa2k.smsforwarder.data.db.LogWithSends
import kotlinx.coroutines.flow.Flow

class LogRepository(private val dao: LogDao) {
    /** Null when an entry with the same [LogEntity.key] already exists (the SMS was seen twice). */
    suspend fun insertIfNew(entry: LogEntity): Long? = dao.insert(entry).takeIf { it != -1L }

    /** Receive times of logged rows with this [looseKey]. */
    suspend fun receivedAtByLooseKey(looseKey: String): List<Long> = dao.receivedAtByLooseKey(looseKey)

    fun recent(limit: Int): Flow<List<LogWithSends>> = dao.recent(limit)

    /** True when this exact SMS ([LogEntity.key]) is already logged: lets catch-up skip it before analysing. */
    suspend fun exists(key: String): Boolean = dao.exists(key)

    /**
     * Deletes logs (and outbox rows) older than 48 h; masks OTP rows older than 1 h and fails their
     * still-queued sends ([OtpExpiry]).
     */
    suspend fun prune(now: Long) = dao.prune(now - RETENTION_MS, OtpExpiry.maskBefore(now))

    /**
     * Log "Clear" ([LogClearPolicy]). The caller moves the catch-up floor to now FIRST (the deleted rows
     * were the dedupe keys) and passes it here: only rows received before it go; rows with a queued send stay.
     */
    suspend fun clear(floor: Long) = dao.clear(floor)

    /** Stored SMS body (masked after 1 h for OTPs), or null when the log row is gone. */
    suspend fun bodyOf(logId: Long): String? = dao.bodyOf(logId)

    companion object {
        const val RETENTION_MS = 48L * 3_600_000
    }
}
