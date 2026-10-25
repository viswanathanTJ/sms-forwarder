package com.viswa2k.smsforwarder.core

/** Default rules for first run / migration (spec 5.3). */
object DefaultRules {
    /**
     * Four rules, ids 0 (assigned by the database), positions 0..3. The first three use [sender]
     * and are enabled only when [destinationIds] is non-empty; "Unknown senders" is always
     * disabled and always Not-in-contacts.
     */
    fun create(destinationIds: List<Long>, sender: SenderCondition): List<Rule> {
        val on = destinationIds.isNotEmpty()
        fun rule(position: Int, name: String, types: Set<MessageType>, all: Boolean, cond: SenderCondition, enabled: Boolean) =
            Rule(0, name, enabled, all, types, cond, destinationIds, Style.SMART, null, position)
        return listOf(
            rule(0, "OTPs", setOf(MessageType.OTP), false, sender, on),
            rule(1, "Bank transactions", setOf(MessageType.TRANSACTION, MessageType.BALANCE), false, sender, on),
            rule(2, "Missed calls", setOf(MessageType.MISSED_CALL), false, sender, on),
            rule(3, "Unknown senders", emptySet(), true, SenderCondition.NOT_IN_CONTACTS, false),
        )
    }
}
