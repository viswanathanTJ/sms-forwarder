package com.viswa2k.smsforwarder.core

/** Per-source type filter, applied before the rules. SMS is never filtered; WhatsApp forwards only the chosen types. */
object SourceFilter {
    /** WhatsApp default: one-time codes only. */
    val DEFAULT_WHATSAPP_TYPES: Set<MessageType> = setOf(MessageType.OTP)

    /** The types the owner can pick for WhatsApp (everything but spam). */
    val SELECTABLE: List<MessageType> = MessageType.entries.filter { it != MessageType.SPAM }

    fun allows(origin: Origin, type: MessageType, waTypes: Set<MessageType>): Boolean = when (origin) {
        Origin.SMS -> true
        Origin.WHATSAPP -> type != MessageType.SPAM && type in waTypes
    }

    /** Log note, e.g. "WhatsApp: only OTP, Transaction forwarded". */
    fun note(waTypes: Set<MessageType>): String =
        "WhatsApp: only " + waTypes.sortedBy { it.code }.joinToString(", ") { it.label } + " forwarded"

    /** Stored codes -> types; unknown/SPAM dropped; empty or missing -> default. */
    fun fromCodes(codes: Collection<String>?): Set<MessageType> =
        codes?.mapNotNull { it.toIntOrNull()?.let(MessageType::fromCode) }?.filter { it != MessageType.SPAM }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_WHATSAPP_TYPES
}
