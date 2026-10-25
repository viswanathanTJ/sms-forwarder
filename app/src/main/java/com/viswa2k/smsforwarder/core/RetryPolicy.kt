package com.viswa2k.smsforwarder.core

/** Outbox backoff (spec 5.6): 30 s doubling, capped at 1 h; give up 24 h after the first attempt. */
object RetryPolicy {
    const val BASE_MS = 30_000L
    const val MAX_DELAY_MS = 3_600_000L
    const val GIVE_UP_MS = 24L * 3_600_000

    /**
     * Epoch ms of the next attempt, or null to give up. [attempts] is the number of attempts already
     * made (0 after the first failure is not yet counted). A server-supplied [retryAfterMs] wins over backoff.
     */
    fun nextAttemptAt(attempts: Int, firstAttemptAt: Long, now: Long, retryAfterMs: Long? = null): Long? {
        if (now - firstAttemptAt >= GIVE_UP_MS) return null
        val backoff = minOf(BASE_MS shl attempts.coerceIn(0, 20), MAX_DELAY_MS)
        return now + (retryAfterMs ?: backoff)
    }
}
