package com.viswa2k.smsforwarder.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExportTest {
    private val dests = listOf(
        Destination(4, Channel.TELEGRAM, "Family <group>", "-100244781", topicId = 17),
        Destination(9, Channel.TELEGRAM, "Me", "51873002"),
        Destination(12, Channel.SMS, "Backup phone", "+919840012345", simSubscriptionId = 2),
    )
    private val rules = listOf(
        Rule(1, "OTPs to me", true, false, setOf(MessageType.OTP), SenderCondition.NOT_IN_CONTACTS, listOf(9), Style.SMART, null, 0),
        Rule(2, "Money", false, false, setOf(MessageType.TRANSACTION, MessageType.BALANCE), SenderCondition.ANYONE, listOf(4, 12), Style.CUSTOM, "%r · %s\\n%m \"q\"", 1),
        Rule(3, "Everything", true, true, emptySet(), SenderCondition.CONTACTS_ONLY, listOf(12), Style.ORIGINAL, null, 2),
    )

    @Test fun roundTrip_withToken() {
        val json = SettingsExport.toJson(rules, dests, "Amma's phone", "123456:ABC-def_ghi")
        val back = SettingsExport.fromJson(json)
        assertEquals(rules, back.rules)
        assertEquals(dests, back.destinations)
        assertEquals("Amma's phone", back.deviceName)
        assertEquals("123456:ABC-def_ghi", back.token)
    }

    @Test fun whatsAppSettingsRoundTrip() {
        val types = setOf(MessageType.OTP, MessageType.BILL)
        val back = SettingsExport.fromJson(SettingsExport.toJson(rules, dests, "x", null, true, types))
        assertEquals(true, back.whatsAppEnabled)
        assertEquals(types, back.whatsAppTypes)
    }

    @Test fun oldFileWithoutWhatsAppKeysLeavesSettingsUntouched() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null, true, setOf(MessageType.BILL)))
        o.remove("whatsAppEnabled"); o.remove("whatsAppTypes")
        val back = SettingsExport.fromJson(o.toString())
        assertNull(back.whatsAppEnabled)
        assertNull(back.whatsAppTypes)
    }

    @Test fun roundTrip_withoutToken_hasNoTokenKey() {
        val json = SettingsExport.toJson(rules, dests, "Pixel", null)
        assertFalse(json.contains("\"token\""))
        assertFalse(JSONObject(json).has("token"))
        val back = SettingsExport.fromJson(json)
        assertNull(back.token)
        assertEquals(rules, back.rules)
        assertEquals(dests, back.destinations)
    }

    @Test fun versionIs2() {
        assertEquals(2, JSONObject(SettingsExport.toJson(rules, dests, "x", null)).getInt("version"))
    }

    @Test fun unknownVersion_isError() {
        val json = JSONObject(SettingsExport.toJson(rules, dests, "x", null)).put("version", 3).toString()
        assertThrows(IllegalArgumentException::class.java) { SettingsExport.fromJson(json) }
    }

    @Test fun missingVersion_isError() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null)).apply { remove("version") }
        assertThrows(IllegalArgumentException::class.java) { SettingsExport.fromJson(o.toString()) }
    }

    @Test fun notJson_isError() {
        assertThrows(IllegalArgumentException::class.java) { SettingsExport.fromJson("hello") }
    }

    @Test fun unknownEnumCode_isError() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null))
        o.getJSONArray("destinations").getJSONObject(0).put("channel", 7)
        assertThrows(IllegalArgumentException::class.java) { SettingsExport.fromJson(o.toString()) }
    }

    @Test fun ruleIdsPointingNowhere_areDropped() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null))
        o.getJSONArray("rules").getJSONObject(0).getJSONArray("destinationIds").put(999)
        assertEquals(listOf(9L), SettingsExport.fromJson(o.toString()).rules[0].destinationIds)
    }

    @Test fun blankToken_isTreatedAsAbsent() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null)).put("token", " ")
        assertNull(SettingsExport.fromJson(o.toString()).token)
    }

    @Test fun spamTypeNeverImported() {
        val o = JSONObject(SettingsExport.toJson(rules, dests, "x", null))
        o.getJSONArray("rules").getJSONObject(0).getJSONArray("types").put(MessageType.SPAM.code)
        assertTrue(MessageType.SPAM !in SettingsExport.fromJson(o.toString()).rules[0].types)
    }

    @Test fun localizeSims_keepsOnlyActiveSubscriptions() {
        val list = listOf(
            Destination(1, Channel.SMS, "a", "+911", simSubscriptionId = 2),
            Destination(2, Channel.SMS, "b", "+912", simSubscriptionId = 5),
            Destination(3, Channel.SMS, "c", "+913"),
            Destination(4, Channel.TELEGRAM, "d", "55"),
        )
        val (out, reset) = SettingsExport.localizeSims(list, setOf(2, 3))
        assertEquals(listOf(2, null, null, null), out.map { it.simSubscriptionId })
        assertEquals(1, reset)
        assertEquals(list.map { it.copy(simSubscriptionId = null) }.map { it.label }, out.map { it.label })
    }

    @Test fun localizeSims_noKnownSubscriptions_clearsAll() {
        val list = listOf(Destination(1, Channel.SMS, "a", "+911", simSubscriptionId = 2))
        val (out, reset) = SettingsExport.localizeSims(list, emptySet())
        assertNull(out.single().simSubscriptionId)
        assertEquals(1, reset)
    }
}
