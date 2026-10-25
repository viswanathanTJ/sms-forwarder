package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val tg1 = Destination(1, Channel.TELEGRAM, "Family", "-100")
    private val tg2 = Destination(2, Channel.TELEGRAM, "Me", "55")
    private val sms = Destination(3, Channel.SMS, "Wife", "09840012345")
    private val dests = listOf(tg1, tg2, sms)

    private fun rule(
        id: Long, position: Int = id.toInt(), enabled: Boolean = true, all: Boolean = false,
        types: Set<MessageType> = setOf(MessageType.OTP), sender: SenderCondition = SenderCondition.ANYONE,
        dest: List<Long> = listOf(1), style: Style = Style.SMART, template: String? = null,
    ) = Rule(id, "r$id", enabled, all, types, sender, dest, style, template, position)

    private fun decide(
        rules: List<Rule>, type: MessageType = MessageType.OTP, kind: SenderKind = SenderKind.BUSINESS,
        sender: String = "VM-HDFCBK", master: Boolean = true,
    ) = RuleEngine.decide(type, kind, sender, master, rules, dests)

    @Test fun masterOffIsPaused() {
        val d = decide(listOf(rule(1)), master = false)
        assertEquals(SkipReason.PAUSED, d.skip)
        assertTrue(d.sends.isEmpty())
        assertTrue(d.matchedRuleIds.isEmpty())
    }

    @Test fun spamNeverMatchesEvenAllMessages() {
        val d = decide(listOf(rule(1, all = true)), type = MessageType.SPAM)
        assertEquals(SkipReason.SPAM, d.skip)
        assertTrue(d.sends.isEmpty())
    }

    @Test fun simpleMatch() {
        val d = decide(listOf(rule(1, dest = listOf(1, 2))))
        assertNull(d.skip)
        assertEquals(listOf(1L), d.matchedRuleIds)
        assertEquals(listOf(tg1, tg2), d.sends.map { it.destination })
        assertTrue(d.sends.all { it.ruleId == 1L })
    }

    @Test fun sharedDestinationSentOnceStyleFromLowerPosition() {
        val rules = listOf(
            rule(10, position = 5, all = true, style = Style.ORIGINAL),
            rule(11, position = 1, style = Style.CUSTOM, template = "T"),
        )
        val d = decide(rules)
        assertEquals(1, d.sends.size)
        assertEquals(11L, d.sends[0].ruleId)
        assertEquals(Style.CUSTOM, d.sends[0].style)
        assertEquals("T", d.sends[0].template)
        assertEquals(listOf(11L, 10L), d.matchedRuleIds)
    }

    @Test fun disabledAndDestinationlessRulesIgnored() {
        val d = decide(listOf(rule(1, enabled = false), rule(2, dest = emptyList())))
        assertEquals(SkipReason.NO_RULE, d.skip)
        assertTrue(d.matchedRuleIds.isEmpty())
    }

    @Test fun unknownDestinationIdsIgnored() {
        val d = decide(listOf(rule(1, dest = listOf(99, 2))))
        assertEquals(listOf(tg2), d.sends.map { it.destination })
        val none = decide(listOf(rule(1, dest = listOf(99))))
        assertEquals(SkipReason.NO_RULE, none.skip)
    }

    @Test fun typeMismatchIsNoRule() {
        val d = decide(listOf(rule(1)), type = MessageType.TRANSACTION)
        assertEquals(SkipReason.NO_RULE, d.skip)
        assertTrue(d.sends.isEmpty())
    }

    @Test fun notInContactsMatchesBusinessAndUnknownNotContact() {
        val r = listOf(rule(1, sender = SenderCondition.NOT_IN_CONTACTS))
        assertEquals(listOf(1L), decide(r, kind = SenderKind.BUSINESS).matchedRuleIds)
        assertEquals(listOf(1L), decide(r, kind = SenderKind.UNKNOWN_NUMBER).matchedRuleIds)
        assertEquals(SkipReason.NO_RULE, decide(r, kind = SenderKind.CONTACT).skip)
    }

    @Test fun contactsOnlyMatchesContact() {
        val r = listOf(rule(1, sender = SenderCondition.CONTACTS_ONLY))
        assertEquals(listOf(1L), decide(r, kind = SenderKind.CONTACT).matchedRuleIds)
        assertEquals(SkipReason.NO_RULE, decide(r, kind = SenderKind.BUSINESS).skip)
    }

    @Test fun unverifiedMatchesOnlyAnyoneRules() {
        val r = listOf(rule(1, sender = SenderCondition.NOT_IN_CONTACTS), rule(2, sender = SenderCondition.ANYONE, dest = listOf(2)))
        val d = decide(r, kind = SenderKind.UNVERIFIED)
        assertEquals(listOf(2L), d.matchedRuleIds)
        assertNull(d.skip)
    }

    @Test fun unverifiedWithNothingLeftIsPermissionMissing() {
        val r = listOf(rule(1, sender = SenderCondition.NOT_IN_CONTACTS), rule(2, sender = SenderCondition.CONTACTS_ONLY))
        val d = decide(r, kind = SenderKind.UNVERIFIED)
        assertEquals(SkipReason.CONTACTS_PERMISSION_MISSING, d.skip)
        assertTrue(d.sends.isEmpty())
    }

    @Test fun unverifiedIsNoRuleWhenNothingWasExcludedBySender() {
        val u = SenderKind.UNVERIFIED
        val ncs = SenderCondition.NOT_IN_CONTACTS
        assertEquals(SkipReason.NO_RULE, decide(listOf(rule(1, sender = ncs)), type = MessageType.TRANSACTION, kind = u).skip)
        assertEquals(SkipReason.NO_RULE, decide(listOf(rule(1, sender = ncs, enabled = false)), kind = u).skip)
        assertEquals(SkipReason.NO_RULE, decide(listOf(rule(1, sender = ncs, dest = listOf(99))), kind = u).skip)
        assertEquals(SkipReason.NO_RULE, decide(emptyList(), kind = u).skip)
    }

    @Test fun loopGuardDropsSmsKeepsTelegram() {
        val d = decide(listOf(rule(1, dest = listOf(1, 3))), sender = "+91 98400 12345", kind = SenderKind.CONTACT)
        assertEquals(listOf(tg1), d.sends.map { it.destination })
        assertEquals(listOf(1L), d.matchedRuleIds)
    }

    @Test fun loopGuardRemovingEverythingKeepsMatchedAndNoSkip() {
        val d = decide(listOf(rule(1, dest = listOf(3))), sender = "09840012345", kind = SenderKind.CONTACT)
        assertEquals(listOf(1L), d.matchedRuleIds)
        assertTrue(d.sends.isEmpty())
        assertNull(d.skip)
    }

    @Test fun loopGuardIgnoresTelegramWithSameNumberAsAddress() {
        val tgNum = Destination(4, Channel.TELEGRAM, "x", "9840012345")
        val d = RuleEngine.decide(MessageType.OTP, SenderKind.CONTACT, "+919840012345", true,
            listOf(rule(1, dest = listOf(4))), listOf(tgNum))
        assertEquals(listOf(tgNum), d.sends.map { it.destination })
    }

    @Test fun rulesEvaluatedInPositionOrderRegardlessOfListOrder() {
        val d = decide(listOf(rule(1, position = 9, dest = listOf(1, 2)), rule(2, position = 0, dest = listOf(2))))
        assertEquals(listOf(2L, 1L), d.matchedRuleIds)
        assertEquals(listOf(tg2, tg1), d.sends.map { it.destination })
        assertEquals(2L, d.sends.first { it.destination == tg2 }.ruleId)
    }

    // Spec 5.3: a sender equal to ANY configured SMS destination gets no SMS sends at all (Telegram still goes).
    @Test fun loopGuardSenderIsAnotherSmsDestinationDropsAllSmsSends() {
        val backup = Destination(5, Channel.SMS, "Backup", "+91 90000 11111")
        val all = listOf(tg1, sms, backup)
        // The rule only targets "Backup"; the sender is "Wife" (another SMS destination): no SMS at all.
        val d = RuleEngine.decide(MessageType.OTP, SenderKind.CONTACT, "+919840012345", true,
            listOf(rule(1, dest = listOf(1, 5))), all)
        assertEquals(listOf(tg1), d.sends.map { it.destination })
        assertEquals(listOf(1L), d.matchedRuleIds)
        assertNull(d.skip)
    }

    @Test fun loopGuardSenderNotAnSmsDestinationKeepsBothSmsSends() {
        val backup = Destination(5, Channel.SMS, "Backup", "+91 90000 11111")
        val d = RuleEngine.decide(MessageType.OTP, SenderKind.CONTACT, "+919812345678", true,
            listOf(rule(1, dest = listOf(3, 5))), listOf(sms, backup))
        assertEquals(listOf(sms, backup), d.sends.map { it.destination })
    }

    @Test fun canForwardNeedsAnEnabledRuleWithAnExistingDestination() {
        assertTrue(RuleEngine.canForward(listOf(rule(1, dest = listOf(1))), dests))
        assertEquals(false, RuleEngine.canForward(listOf(rule(1, enabled = false, dest = listOf(1))), dests))
        assertEquals(false, RuleEngine.canForward(listOf(rule(1, dest = emptyList())), dests))
        assertEquals(false, RuleEngine.canForward(listOf(rule(1, dest = listOf(99))), dests))
        assertEquals(false, RuleEngine.canForward(emptyList(), dests))
    }
}
