package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.TestKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MessageCheckTest {
    private val analyzer = MessageAnalyzer(TestKeywords.lists)
    private val me = Destination(1, Channel.TELEGRAM, "Me", "51873002")
    private val family = Destination(2, Channel.TELEGRAM, "Family group", "-100244781")
    private val backup = Destination(3, Channel.SMS, "Backup phone", "+919840012345")
    private val dests = listOf(me, family, backup)
    private val money = Rule(10, "Money alerts", true, false, setOf(MessageType.TRANSACTION), SenderCondition.ANYONE, listOf(2, 3), Style.SMART, null, 0)
    private val otps = Rule(11, "OTPs", true, false, setOf(MessageType.OTP), SenderCondition.NOT_IN_CONTACTS, listOf(1), Style.SMART, null, 1)
    private val debit = "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank"
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun run(
        sender: String, body: String, kind: SenderKind = SenderKind.BUSINESS, rules: List<Rule> = listOf(money, otps),
        masterOn: Boolean = true,
    ) = MessageCheck.run(sender, body, kind, analyzer, rules, dests, "Pixel 8", now = 1_792_922_460_000L, masterOn = masterOn, zone = zone)

    private val everything = Rule(12, "Everything", true, true, emptySet(), SenderCondition.ANYONE, listOf(1), Style.SMART, null, 2)

    @Test fun whatsAppSourceFilterBlocksNonSelectedTypesBeforeRules() {
        val r = MessageCheck.run(
            "WhatsApp: Shop", debit, SenderKind.UNVERIFIED, analyzer, listOf(everything), dests, "Pixel 8",
            now = 1_792_922_460_000L, masterOn = true, zone = zone, origin = Origin.WHATSAPP,
        )
        assertEquals(SkipReason.SOURCE_FILTERED, r.skip)
        assertTrue(r.destinations.isEmpty())
        assertNull(r.telegramPreview)
    }

    @Test fun whatsAppOtpPassesTheFilter() {
        val r = MessageCheck.run(
            "WhatsApp: Bank", "482913 is your OTP. Do not share it with anyone.", SenderKind.UNVERIFIED, analyzer,
            listOf(everything), dests, "Pixel 8", now = 1_792_922_460_000L, masterOn = true, zone = zone, origin = Origin.WHATSAPP,
        )
        assertNull(r.skip)
        assertEquals(listOf("Me"), r.destinations)
    }

    @Test fun smsIgnoresTheWhatsAppFilter() {
        assertNull(run("VM-HDFCBK", debit, rules = listOf(everything)).skip)
    }

    @Test fun mockDebitSample() {
        val r = run("VM-HDFCBK", debit)
        assertEquals(MessageType.TRANSACTION, r.type)
        assertEquals("₹1,250 debit", r.extracted)
        assertEquals(SenderKind.BUSINESS, r.senderKind)
        assertEquals(listOf("Money alerts"), r.matchedRules)
        assertEquals(listOf("Family group", "Backup phone"), r.destinations)
        assertNull(r.skip)
        val tg = r.telegramPreview!!
        assertTrue(tg, tg.contains("1,250"))
        assertTrue(r.smsPreview!!.isNotBlank())
    }

    @Test fun otpGoesToMe_previewIsTelegramOnly() {
        val r = run("AD-AMAZON", "482913 is your Amazon OTP. Do not share it with anyone.")
        assertEquals(MessageType.OTP, r.type)
        assertEquals("Code 482913", r.extracted)
        assertEquals(listOf("OTPs"), r.matchedRules)
        assertEquals(listOf("Me"), r.destinations)
        assertNull(r.smsPreview)
    }

    @Test fun noRule_hasSkipAndNoPreview() {
        val r = run("VM-HDFCBK", debit, rules = listOf(otps))
        assertEquals(SkipReason.NO_RULE, r.skip)
        assertTrue(r.matchedRules.isEmpty())
        assertTrue(r.destinations.isEmpty())
        assertNull(r.telegramPreview)
        assertNull(r.smsPreview)
    }

    @Test fun pausedStillDecidesAsIfOn() {
        // Real paused state: the check still shows what the rules would do, and says forwarding is paused.
        val r = run("VM-HDFCBK", debit, masterOn = false)
        assertNull(r.skip)
        assertEquals(listOf("Money alerts"), r.matchedRules)
        assertEquals(listOf("Family group", "Backup phone"), r.destinations)
        assertTrue(r.paused)
        assertEquals(false, run("VM-HDFCBK", debit).paused)
    }

    @Test fun ruleNameOrderFollowsPosition() {
        val all = Rule(12, "All", true, true, emptySet(), SenderCondition.ANYONE, listOf(1), Style.ORIGINAL, null, -1)
        val r = run("VM-HDFCBK", debit, rules = listOf(money, all))
        assertEquals(listOf("All", "Money alerts"), r.matchedRules)
        assertEquals(listOf("Me", "Family group", "Backup phone"), r.destinations)
    }
}
