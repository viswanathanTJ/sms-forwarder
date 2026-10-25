package com.viswa2k.smsforwarder.core

/**
 * Oldest inbox message the catch-up scan may forward. At most 24 h (shorter than the 48 h log retention
 * that holds the dedupe keys) and never before [floor]: first run or the moment forwarding was switched on,
 * so old history and messages received while paused are never forwarded.
 */
object CatchUpWindow {
    const val WINDOW_MS = 24L * 3_600_000

    fun since(now: Long, floor: Long): Long = maxOf(now - WINDOW_MS, floor)

    sealed interface Plan {
        /** Floor unset: record `now` as the floor and forward nothing. */
        data object FirstRun : Plan
        data class Scan(val since: Long) : Plan
    }

    fun plan(now: Long, floor: Long): Plan = if (floor == 0L) Plan.FirstRun else Plan.Scan(since(now, floor))
}
