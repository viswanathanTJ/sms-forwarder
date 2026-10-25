package com.viswa2k.smsforwarder.core

/** Why an SMS was not forwarded. [code] is stored; never renumber. */
enum class SkipReason(val code: Int) {
    PAUSED(1), SPAM(2), NO_RULE(3), CONTACTS_PERMISSION_MISSING(4);

    companion object {
        fun fromCode(code: Int): SkipReason? = entries.firstOrNull { it.code == code }
    }
}

data class PlannedSend(val destination: Destination, val ruleId: Long, val style: Style, val template: String?)

/**
 * Outcome of rule evaluation. [skip] is null when at least one rule matched, even if
 * the loop guard then left [sends] empty (the Log shows "matched, nothing sent").
 * [SkipReason.CONTACTS_PERMISSION_MISSING] only when an UNVERIFIED sender's enabled, type-matching rule
 * with an existing destination was excluded solely by its sender condition; otherwise [SkipReason.NO_RULE].
 */
data class Decision(val matchedRuleIds: List<Long>, val sends: List<PlannedSend>, val skip: SkipReason?)

/** Pure rule evaluation (spec 5.3). */
object RuleEngine {
    fun decide(
        type: MessageType,
        senderKind: SenderKind,
        senderAddress: String,
        masterOn: Boolean,
        rules: List<Rule>,
        destinations: List<Destination>,
    ): Decision {
        if (!masterOn) return Decision(emptyList(), emptyList(), SkipReason.PAUSED)
        if (type == MessageType.SPAM) return Decision(emptyList(), emptyList(), SkipReason.SPAM)

        val byId = destinations.associateBy { it.id }
        val candidates = rules
            .sortedWith(compareBy<Rule> { it.position }.thenBy { it.id })
            .filter { r -> r.enabled && r.destinationIds.any { it in byId } }
            .filter { it.allMessages || type in it.types }
        val matched = candidates.filter { senderMatches(it.sender, senderKind) }

        if (matched.isEmpty()) {
            // Only blame the contacts permission when a sender condition alone excluded a rule.
            val reason = if (senderKind == SenderKind.UNVERIFIED && candidates.isNotEmpty()) {
                SkipReason.CONTACTS_PERMISSION_MISSING
            } else SkipReason.NO_RULE
            return Decision(emptyList(), emptyList(), reason)
        }

        val chosen = LinkedHashMap<Long, PlannedSend>()
        for (rule in matched) {
            for (id in rule.destinationIds) {
                val dest = byId[id] ?: continue
                if (id !in chosen) chosen[id] = PlannedSend(dest, rule.id, rule.style, rule.customTemplate)
            }
        }
        // Loop guard: a sender that is ANY configured SMS destination gets no SMS sends at all (Telegram still goes).
        val senderIsSmsDestination = destinations.any {
            it.channel == Channel.SMS && PhoneNumbers.same(senderAddress, it.address)
        }
        val sends = if (senderIsSmsDestination) {
            chosen.values.filterNot { it.destination.channel == Channel.SMS }
        } else chosen.values.toList()
        return Decision(matched.map { it.id }, sends, null)
    }

    /** True when at least one enabled rule has a destination that exists (otherwise nothing can forward). */
    fun canForward(rules: List<Rule>, destinations: List<Destination>): Boolean {
        val ids = destinations.map { it.id }.toSet()
        return rules.any { r -> r.enabled && r.destinationIds.any { it in ids } }
    }

    private fun senderMatches(cond: SenderCondition, kind: SenderKind): Boolean = when (cond) {
        SenderCondition.ANYONE -> true
        SenderCondition.NOT_IN_CONTACTS -> kind == SenderKind.BUSINESS || kind == SenderKind.UNKNOWN_NUMBER
        SenderCondition.CONTACTS_ONLY -> kind == SenderKind.CONTACT
    }
}
