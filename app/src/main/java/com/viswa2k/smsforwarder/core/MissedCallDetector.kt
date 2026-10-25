package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.SenderIds

/** A carrier "missed call" alert. [time] is kept as written in the SMS; [count] defaults to 1. */
data class MissedCall(val caller: String?, val time: String?, val count: Int)

/**
 * Detects carrier missed-call alert SMS (Airtel, Jio, Vi, BSNL). Fires only for business /
 * short-code senders, so a friend texting "missed your call" is never a missed-call alert.
 * The caller number must be anchored to the cue phrase ([PATTERNS]); instruction messages
 * ("give a missed call on 9223...") and toll-free numbers are rejected. To support a new carrier
 * wording, add a pattern to [PATTERNS] (and a test).
 */
object MissedCallDetector {
    /**
     * Phone-like token: `+91 99620 55555` (2-5-5, '+' required), plain 7-13 digits with optional '+',
     * or a `99620 55555` pair. Spaces are allowed only in these groupings, so a following
     * space-separated date/digit group is never swallowed; '-' is not allowed, so dates are skipped.
     */
    private const val NUM = "((?<![\\d:])(?:\\+\\d{2} ?\\d{5} ?\\d{5}|\\d{5} \\d{5}|\\+?\\d{7,13})(?![\\d:]))"

    /** Ordered patterns; group 1 is the caller. [needsMissedCue]: "called you" alone is too weak (delivery calls). */
    private class Pattern(val regex: Regex, val needsMissedCue: Boolean = false)

    private val PATTERNS = listOf(
        // Airtel / Vi: "missed call from +91..", "2 missed calls from 98.."
        Pattern(Regex("(?i)missed calls?\\s+(?:alert\\s*)?(?:from|by)\\s+$NUM")),
        // BSNL: "Missed call alert: 0442... called you at 14:02"
        Pattern(Regex("(?i)(?:missed call|call) alert\\s*:\\s*$NUM")),
        // Jio: "98.. tried to call you", "98.. tried calling you"
        Pattern(Regex("(?i)$NUM\\s+(?:has\\s+)?tried (?:to call|calling) you")),
        Pattern(Regex("(?i)$NUM\\s+(?:has\\s+)?called you"), needsMissedCue = true),
    )

    /** "Give a missed call on 9223..", "miss call to ..": instructions, not alerts. */
    private val INSTRUCTION = Regex("(?i)\\b(?:give|dial|make|do)\\s+(?:a\\s+)?missed call|\\bmiss(?:ed)? call\\s+(?:on|to|at)\\b")
    private val MISSED_CUE = Regex("(?i)missed call|call alert")
    private val TOLL_FREE = Regex("^(?:\\+?91)?0?1(?:800|860)")
    private val TIME = Regex("(?i)\\b\\d{1,2}:\\d{2}(?: ?[AP]M)?")
    private val COUNT = Regex("(?i)\\b(\\d+) missed calls\\b")

    fun detect(sender: String, body: String): MissedCall? {
        if (!SenderIds.parse(sender).isBusiness) return null
        if (INSTRUCTION.containsMatchIn(body)) return null

        val senderDigits = sender.filter { it.isDigit() }
        val hasMissedCue = MISSED_CUE.containsMatchIn(body)
        val caller = PATTERNS.asSequence()
            .filter { !it.needsMissedCue || hasMissedCue }
            .flatMap { p -> p.regex.findAll(body).map { it.groupValues[1].replace(" ", "") } }
            .firstOrNull { c ->
                val d = c.filter(Char::isDigit)
                !TOLL_FREE.containsMatchIn(d) && !sameNumber(d, senderDigits)
            } ?: return null

        val time = TIME.find(body)?.value
        val count = COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        return MissedCall(caller, time, count)
    }

    private fun sameNumber(a: String, b: String): Boolean =
        b.isNotEmpty() && (a == b || (a.length >= 7 && b.length >= 7 && a.takeLast(7) == b.takeLast(7)))
}
