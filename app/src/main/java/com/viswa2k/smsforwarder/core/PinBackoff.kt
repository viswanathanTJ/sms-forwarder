package com.viswa2k.smsforwarder.core

/**
 * Wrong-PIN back-off: after [FREE_TRIES] wrong entries, entry is locked for 30 s, doubling with each
 * further wrong entry up to 5 min. A correct PIN resets it. Persisted as "failures:lockedUntil".
 */
object PinBackoff {
    data class State(val failures: Int = 0, val lockedUntil: Long = 0L)

    const val FREE_TRIES = 5
    private const val BASE_MS = 30_000L
    const val MAX_MS = 300_000L

    fun onFailure(s: State, now: Long): State {
        val failures = s.failures + 1
        if (failures < FREE_TRIES) return State(failures, 0L)
        val shift = (failures - FREE_TRIES).coerceAtMost(10)
        val lock = (BASE_MS shl shift).coerceAtMost(MAX_MS)
        return State(failures, now + lock)
    }

    fun onSuccess(): State = State()

    /** Milliseconds until the next try is allowed; never more than [MAX_MS] (clock moved back). */
    fun remainingMs(s: State, now: Long): Long = (s.lockedUntil - now).coerceIn(0L, MAX_MS)

    fun encode(s: State): String = "${s.failures}:${s.lockedUntil}"

    fun decode(text: String): State {
        val parts = text.split(":")
        if (parts.size != 2) return State()
        val f = parts[0].toIntOrNull() ?: return State()
        val until = parts[1].toLongOrNull() ?: return State()
        return State(f.coerceAtLeast(0), until)
    }
}
