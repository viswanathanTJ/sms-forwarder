package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.SenderIds

/** A carrier "missed call" alert. [time] is kept as written in the SMS; [count] defaults to 1. */
data class MissedCall(val caller: String?, val time: String?, val count: Int)

/**
 * Detects carrier missed-call alert SMS (Airtel, Jio, Vi, BSNL). Fires only for business /
 * short-code senders, so a friend texting "missed your call" is never a missed-call alert.
 * To support a new carrier wording, add a cue to [CUES] (and a test).
 */
object MissedCallDetector {
    /** Ordered cue phrases; any match qualifies the body. Extend here. */
    private val CUES = listOf(
        Regex("(?i)missed calls?"),
        Regex("(?i)tried to call you"),
        Regex("(?i)tried calling you"),
        Regex("(?i)call alert"),
        Regex("(?i)called you"),
    )

    /** Phone-like token: optional '+', 7-13 digits, groups may be separated by single spaces (not '-', so dates are skipped). */
    private val PHONE = Regex("(?<![\\d:])\\+?\\d(?: ?\\d){6,12}(?![\\d:])")
    private val TIME = Regex("(?i)\\b\\d{1,2}:\\d{2}(?: ?[AP]M)?")
    private val COUNT = Regex("(?i)\\b(\\d+) missed calls\\b")

    fun detect(sender: String, body: String): MissedCall? {
        if (!SenderIds.parse(sender).isBusiness) return null
        if (CUES.none { it.containsMatchIn(body) }) return null

        val senderDigits = sender.filter { it.isDigit() }
        val caller = PHONE.findAll(body)
            .map { it.value.replace(" ", "") }
            .firstOrNull { !sameNumber(it.filter(Char::isDigit), senderDigits) }
            ?: return null

        val time = TIME.find(body)?.value
        val count = COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        return MissedCall(caller, time, count)
    }

    private fun sameNumber(a: String, b: String): Boolean =
        b.isNotEmpty() && (a == b || (a.length >= 7 && b.length >= 7 && a.takeLast(7) == b.takeLast(7)))
}
