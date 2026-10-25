package com.viswa2k.smsforwarder.core

/**
 * Dedupe key of one SMS (spec review focus 2). Seconds, normalized sender and trimmed body make the key
 * identical whether the SMS arrived live or is read back from the inbox.
 */
object MessageKey {
    fun of(sender: String, sentAt: Long, body: String): String =
        "${PhoneNumbers.normalize(sender)}|${sentAt / 1000}|${body.trim().hashCode()}"
}
