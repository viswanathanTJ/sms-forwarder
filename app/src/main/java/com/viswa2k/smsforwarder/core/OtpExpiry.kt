package com.viswa2k.smsforwarder.core

/**
 * Ruling 30: an OTP not sent within 1 hour of receipt is never sent. When the log row is masked its
 * still-queued sends become FAILED with [EXPIRED_ERROR] (never sent with the masked text), and a
 * resend of an expired or masked OTP is refused.
 */
object OtpExpiry {
    const val AFTER_MS = 3_600_000L
    const val EXPIRED_ERROR = "OTP expired (not sent within 1 hour)"

    /** Logs received before this instant are masked (and their pending sends failed). */
    fun maskBefore(now: Long): Long = now - AFTER_MS

    fun isExpired(type: MessageType, receivedAt: Long, now: Long): Boolean =
        type == MessageType.OTP && receivedAt < maskBefore(now)

    data class Send(val outboxId: Long, val pending: Boolean)

    /** Outbox rows to mark FAILED when their OTP log is masked: the ones still queued. */
    fun sendsToFail(sends: List<Send>): List<Long> = sends.filter { it.pending }.map { it.outboxId }

    fun mayResend(masked: Boolean, type: MessageType, receivedAt: Long, now: Long): Boolean =
        !masked && !isExpired(type, receivedAt, now)
}
