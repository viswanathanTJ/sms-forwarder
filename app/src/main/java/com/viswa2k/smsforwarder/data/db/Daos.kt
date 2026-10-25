package com.viswa2k.smsforwarder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.viswa2k.smsforwarder.core.LogClearPolicy
import com.viswa2k.smsforwarder.core.LogMasker
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.OtpExpiry
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RuleDao {
    @Query("SELECT * FROM rules ORDER BY position, id") abstract fun observe(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules ORDER BY position, id") abstract suspend fun all(): List<RuleEntity>
    @Query("SELECT COALESCE(MAX(position), -1) FROM rules") abstract suspend fun maxPosition(): Int
    @Insert abstract suspend fun insert(rule: RuleEntity): Long
    @Upsert abstract suspend fun upsert(rule: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id") abstract suspend fun delete(id: Long)
    @Query("DELETE FROM rules") abstract suspend fun deleteAll()
    @Query("UPDATE rules SET enabled = :on WHERE id = :id") abstract suspend fun setEnabled(id: Long, on: Boolean)
    @Query("UPDATE rules SET position = :position WHERE id = :id") abstract suspend fun setPosition(id: Long, position: Int)

    @Transaction
    open suspend fun save(rule: RuleEntity): Long {
        if (rule.id == 0L) return insert(rule.copy(position = maxPosition() + 1))
        upsert(rule)
        return rule.id
    }

    /** Moves rule [id] to [newPosition] (clamped) and renumbers all positions 0..n-1. */
    @Transaction
    open suspend fun move(id: Long, newPosition: Int) {
        val ids = all().map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        ids.add(newPosition.coerceIn(0, ids.size), id)
        ids.forEachIndexed { index, rid -> setPosition(rid, index) }
    }
}

@Dao
abstract class DestinationDao {
    @Query("SELECT * FROM destinations ORDER BY id") abstract fun observe(): Flow<List<DestinationEntity>>
    @Query("SELECT * FROM destinations ORDER BY id") abstract suspend fun all(): List<DestinationEntity>
    @Insert abstract suspend fun insert(destination: DestinationEntity): Long
    @Upsert abstract suspend fun upsert(destination: DestinationEntity)
    @Query("DELETE FROM destinations WHERE id = :id") abstract suspend fun delete(id: Long)
    @Query("DELETE FROM destinations") abstract suspend fun deleteAll()

    @Transaction
    open suspend fun save(destination: DestinationEntity): Long {
        if (destination.id == 0L) return insert(destination)
        upsert(destination)
        return destination.id
    }
}

@Dao
abstract class LogDao {
    /** Returns the new row id, or -1 when [LogEntity.key] already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(entry: LogEntity): Long

    /** Cheap pre-check before analysing an SMS; [insert]'s unique key stays the authority. */
    @Query("SELECT EXISTS(SELECT 1 FROM logs WHERE key = :key)") abstract suspend fun exists(key: String): Boolean

    @Query("SELECT receivedAt FROM logs WHERE looseKey = :looseKey")
    abstract suspend fun receivedAtByLooseKey(looseKey: String): List<Long>

    @Transaction
    @Query("SELECT * FROM logs ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    abstract fun recent(limit: Int): Flow<List<LogWithSends>>

    /** Outbox rows go with their log (foreign key cascade). */
    @Query("DELETE FROM logs WHERE receivedAt < :cutoff") abstract suspend fun deleteOlderThan(cutoff: Long)
    @Query("SELECT * FROM logs WHERE type = :otpType AND masked = 0 AND receivedAt < :cutoff")
    abstract suspend fun unmaskedOtpBefore(otpType: Int, cutoff: Long): List<LogEntity>
    @Query("UPDATE logs SET body = :body, extracted = :extracted, masked = 1, otpCode = NULL WHERE id = :id")
    abstract suspend fun markMasked(id: Long, body: String, extracted: String)
    @Query("SELECT * FROM outbox WHERE logId = :logId") abstract suspend fun outboxFor(logId: Long): List<OutboxEntity>
    @Query("UPDATE outbox SET text = :text WHERE id = :id") abstract suspend fun setOutboxText(id: Long, text: String)
    /** Expires a still-queued send (no attempt is counted). */
    @Query("UPDATE outbox SET state = 2, lastError = :error WHERE id = :id AND state = 0")
    abstract suspend fun expireSend(id: Long, error: String)
    @Query("SELECT body FROM logs WHERE id = :id") abstract suspend fun bodyOf(id: Long): String?

    @Query("SELECT id, receivedAt FROM logs") abstract suspend fun idsAndTimes(): List<LogIdTime>
    @Query("SELECT DISTINCT logId FROM outbox WHERE state = 0") abstract suspend fun logIdsWithPendingSends(): List<Long>
    @Query("DELETE FROM logs WHERE id IN (:ids)") abstract suspend fun deleteIds(ids: List<Long>)

    /** Log "Clear": deletes rows received before [floor] except those with a queued send ([LogClearPolicy]). */
    @Transaction
    open suspend fun clear(floor: Long) {
        val pending = logIdsWithPendingSends().toSet()
        val rows = idsAndTimes().map { LogClearPolicy.Row(it.id, it.receivedAt, listOf(it.id in pending)) }
        // Chunked: API 29's SQLite allows 999 bound variables.
        LogClearPolicy.toDelete(rows, floor).chunked(500).forEach { deleteIds(it) }
    }

    /**
     * Deletes rows older than [deleteBefore]; masks OTP rows received before [maskBefore]. Ruling 30: the
     * masked log's still-queued sends become FAILED ([OtpExpiry.EXPIRED_ERROR]) first, so masked text is
     * never sent.
     */
    @Transaction
    open suspend fun prune(deleteBefore: Long, maskBefore: Long) {
        deleteOlderThan(deleteBefore)
        for (row in unmaskedOtpBefore(MessageType.OTP.code, maskBefore)) {
            val sends = outboxFor(row.id)
            OtpExpiry.sendsToFail(sends.map { OtpExpiry.Send(it.id, it.state == OutboxEntity.STATE_PENDING) })
                .forEach { expireSend(it, OtpExpiry.EXPIRED_ERROR) }
            markMasked(row.id, LogMasker.mask(row.body, row.otpCode), LogMasker.mask(row.extracted, row.otpCode))
            for (send in sends) setOutboxText(send.id, LogMasker.mask(send.text, row.otpCode))
        }
    }
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE state = 0 AND nextAttemptAt <= :now ORDER BY firstAttemptAt, id")
    suspend fun due(now: Long): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id") suspend fun byId(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE state = 0 ORDER BY firstAttemptAt, id")
    suspend fun pending(): List<OutboxEntity>

    /** Pushes a destination's still-pending rows back to [nextAt] (never earlier than they already are). */
    @Query("UPDATE outbox SET nextAttemptAt = :nextAt WHERE destinationId = :destinationId AND state = 0 AND nextAttemptAt < :nextAt")
    suspend fun deferPending(destinationId: Long, nextAt: Long)

    @Query("SELECT MIN(nextAttemptAt) FROM outbox WHERE state = 0")
    suspend fun earliestPending(): Long?

    // Outcome updates apply only to a still-PENDING row: a row already FAILED (e.g. "OTP expired" by a
    // concurrent prune) or SENT is never changed by a late send outcome.
    @Query("UPDATE outbox SET state = 1, attempts = attempts + 1, lastError = NULL WHERE id = :id AND state = 0")
    suspend fun markSent(id: Long)

    @Query("UPDATE outbox SET state = 0, attempts = attempts + 1, nextAttemptAt = :nextAt, lastError = :error WHERE id = :id AND state = 0")
    suspend fun markRetry(id: Long, nextAt: Long, error: String?)

    @Query("UPDATE outbox SET state = 2, attempts = attempts + 1, lastError = :error WHERE id = :id AND state = 0")
    suspend fun markFailed(id: Long, error: String?)

    /** Oldest first-attempt time of a still-unresolved error on [destinationId], newer than its last success. */
    @Query(
        """
        SELECT MIN(firstAttemptAt) FROM outbox
        WHERE destinationId = :destinationId AND state IN (0, 2) AND lastError IS NOT NULL
          AND firstAttemptAt > COALESCE(
                (SELECT MAX(firstAttemptAt) FROM outbox WHERE destinationId = :destinationId AND state = 1), 0)
        """,
    )
    suspend fun failingSince(destinationId: Long): Long?

    /** Most recent error text of an unresolved (pending-retry or failed) row for [destinationId]. */
    @Query("SELECT lastError FROM outbox WHERE destinationId = :destinationId AND state IN (0, 2) AND lastError IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastErrorFor(destinationId: Long): String?

    /** The log row an outbox row belongs to. */
    @Query("SELECT logs.* FROM logs JOIN outbox ON outbox.logId = logs.id WHERE outbox.id = :outboxId")
    suspend fun logOf(outboxId: Long): LogEntity?

    /** FAILED -> new PENDING. Never for a masked log (atomic backstop for [com.viswa2k.smsforwarder.core.OtpExpiry.mayResend]). */
    @Query(
        """
        UPDATE outbox SET state = 0, attempts = 0, firstAttemptAt = :now, nextAttemptAt = :now, lastError = NULL
        WHERE id = :id AND state = 2 AND logId IN (SELECT id FROM logs WHERE masked = 0)
        """,
    )
    suspend fun resend(id: Long, now: Long)
}

/** Projection for [LogDao.clear]. */
data class LogIdTime(val id: Long, val receivedAt: Long)
