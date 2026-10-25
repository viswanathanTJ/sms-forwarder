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

    /**
     * The one decision path: master off -> PAUSED, SPAM -> SPAM, WhatsApp type filter -> SOURCE_FILTERED, then the rules.
     * Spam and paused keep their own Log reason.
     */
    fun decide(
        origin: Origin, type: MessageType, waTypes: Set<MessageType>, masterOn: Boolean,
        senderKind: SenderKind, senderAddress: String, rules: List<Rule>, destinations: List<Destination>,
    ): Decision = when {
        !masterOn -> Decision(emptyList(), emptyList(), SkipReason.PAUSED)
        type == MessageType.SPAM -> Decision(emptyList(), emptyList(), SkipReason.SPAM)
        !allows(origin, type, waTypes) -> Decision(emptyList(), emptyList(), SkipReason.SOURCE_FILTERED)
        else -> RuleEngine.decide(type, senderKind, senderAddress, masterOn, rules, destinations)
    }

    /** Log note, e.g. "WhatsApp: only OTP, Transaction forwarded". */
    fun note(waTypes: Set<MessageType>): String =
        "WhatsApp: only " + waTypes.sortedBy { it.code }.joinToString(", ") { it.label } + " forwarded"

    /** Stored codes -> types; unknown/SPAM dropped; empty or missing -> default. */
    fun fromCodes(codes: Collection<String>?): Set<MessageType> =
        codes?.mapNotNull { it.toIntOrNull()?.let(MessageType::fromCode) }?.filter { it != MessageType.SPAM }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_WHATSAPP_TYPES
}
