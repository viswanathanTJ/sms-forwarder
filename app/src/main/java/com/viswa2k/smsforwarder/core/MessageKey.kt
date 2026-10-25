package com.viswa2k.smsforwarder.core

/**
 * Dedupe key of one SMS (spec review focus 2). Seconds, normalized sender and trimmed body make the key
 * identical whether the SMS arrived live or is read back from the inbox.
 */
object MessageKey {
    fun of(sender: String, sentAt: Long, body: String): String =
        "${PhoneNumbers.normalize(sender)}|${sentAt / 1000}|${body.trim().hashCode()}"

    /** Time-free key for inbox rows whose `date_sent` is missing (their exact key cannot match the live one). */
    fun loose(sender: String, body: String): String = "${PhoneNumbers.normalize(sender)}|${body.trim().hashCode()}"

    const val LOOSE_WINDOW_MS = 120_000L

    /** True when a row with the same [loose] key was logged within +-2 min of the inbox [date]. */
    fun isLooseDuplicate(loggedReceivedAts: List<Long>, date: Long): Boolean =
        loggedReceivedAts.any { kotlin.math.abs(it - date) <= LOOSE_WINDOW_MS }
}
