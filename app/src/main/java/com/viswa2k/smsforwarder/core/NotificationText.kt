package com.viswa2k.smsforwarder.core

/** One message read from a WhatsApp notification ([person] = group member; null in 1:1 chats). */
data class WaMessage(val chat: String, val person: String?, val text: String, val timestamp: Long)

/**
 * Pure decisions for the WhatsApp notification source (spec addendum 2026-10-25-whatsapp-source-design):
 * which packages, which texts and which timestamps are real new messages, and the `WA:` sender identity.
 */
object NotificationText {
    const val PREFIX = "WA:"
    /** Separates group and member in a sender address: `WA:Family · Mom`. */
    const val SEP = " · "
    /** MessagingStyle re-posts history: a message older than this before the post is never forwarded. */
    const val MAX_AGE_MS = 10 * 60_000L

    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun isAllowedPackage(pkg: String?): Boolean = pkg != null && pkg in PACKAGES

    private val HOUSEKEEPING = listOf(
        Regex("""\d+ new messages?"""),
        Regex("""checking for new messages"""),
        Regex("""\d+ messages? from \d+ chats?"""),
        Regex("""(backup in progress|backing up (messages|media|chats?)|restoring (messages|media|chats?)|preparing backup|backup (complete|paused|failed)).*"""),
        Regex("""(incoming|ongoing|missed) (voice |video )?call"""),
        Regex("""\d+ missed (voice |video )?calls?"""),
        Regex("""(calling|ringing)(…|\.\.\.)?"""),
    )

    /** WhatsApp's own status texts (counters, backup, calls): not messages. */
    fun housekeeping(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.isNotEmpty() && HOUSEKEEPING.any { it.matches(t) }
    }

    private val COUNT_SUFFIX = Regex("""\s*\(\d+ (new )?messages?\)$""", RegexOption.IGNORE_CASE)
    private val BIDI = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")

    /**
     * A notification title as a stable chat name: bidi marks removed and WhatsApp's " (3 messages)" counter
     * dropped, so the sender address (and the dedupe key) does not change as unread messages pile up.
     */
    fun cleanTitle(title: String): String = title.replace(BIDI, "").replace(COUNT_SUFFIX, "").trim()

    /** `WA:<chat>`, or `WA:<group> · <person>` when a different, non-blank member wrote in a group. */
    fun senderAddress(chat: String, person: String?): String {
        val c = cleanTitle(chat)
        val p = person?.let(::cleanTitle).orEmpty()
        return if (p.isEmpty() || p.equals(c, ignoreCase = true)) "$PREFIX$c" else "$PREFIX$c$SEP$p"
    }

    fun isWhatsAppAddress(address: String): Boolean = address.trimStart().startsWith(PREFIX, ignoreCase = true)

    /** The part after `WA:` (chat, plus member for groups). */
    private fun rest(address: String): String = address.trimStart().substring(PREFIX.length).trim()

    /** "WhatsApp · Amazon" for `WA:Amazon`; any other address unchanged. */
    fun displayName(address: String): String =
        if (isWhatsAppAddress(address)) "WhatsApp$SEP${rest(address)}" else address

    /** "WhatsApp: Amazon" for the custom template's `%s`; any other address unchanged. */
    fun templateName(address: String): String =
        if (isWhatsAppAddress(address)) "WhatsApp: ${rest(address)}" else address

    /** Chat title of a `WA:` address (the group for group messages). */
    fun chatOf(address: String): String = rest(address).substringBefore(SEP).trim()

    /** Not older than [MAX_AGE_MS] before [postTime] and not before the toggle was switched on ([waFloor]). */
    fun acceptTimestamp(messageTs: Long, postTime: Long, waFloor: Long): Boolean =
        messageTs >= waFloor && messageTs >= postTime - MAX_AGE_MS

    /** Spec §2 filters for one extracted message. */
    fun accept(m: WaMessage, postTime: Long, waFloor: Long): Boolean =
        m.chat.isNotBlank() && m.text.isNotBlank() && !housekeeping(m.text) && acceptTimestamp(m.timestamp, postTime, waFloor)
}
