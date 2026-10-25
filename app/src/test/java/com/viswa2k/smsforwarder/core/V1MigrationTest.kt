package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1MigrationTest {
    private fun v1(
        token: String = "123:abc",
        chats: List<String> = listOf("111", "222"),
        sms: String = "",
        alias: String = "",
        serviceOn: Boolean = true,
        telegramOn: Boolean = true,
        smsOn: Boolean = false,
        skip: Boolean = false,
    ) = V1Settings(token, chats, sms, alias, serviceOn, telegramOn, smsOn, skip)

    @Test fun twoChatsBecomeTelegramDestinations() {
        val plan = V1Migration.plan(v1(chats = V1Migration.splitChats("111, 222")))
        assertEquals(
            listOf(
                Destination(0, Channel.TELEGRAM, "Chat 1", "111"),
                Destination(0, Channel.TELEGRAM, "Chat 2", "222"),
            ),
            plan.destinations,
        )
        assertEquals(listOf(0, 1), plan.assignToRules)
        assertEquals("123:abc", plan.token)
    }

    @Test fun telegramOffSmsOnAssignsOnlySms() {
        val plan = V1Migration.plan(v1(sms = "+919999999999", telegramOn = false, smsOn = true))
        assertEquals(3, plan.destinations.size)
        assertEquals(Channel.SMS, plan.destinations[2].channel)
        assertEquals("+919999999999", plan.destinations[2].address)
        assertEquals(listOf(2), plan.assignToRules)
    }

    @Test fun skipContactsUsesNotInContacts() {
        assertEquals(SenderCondition.NOT_IN_CONTACTS, V1Migration.plan(v1(skip = true)).sender)
        assertEquals(SenderCondition.ANYONE, V1Migration.plan(v1(skip = false)).sender)
    }

    @Test fun blankAliasGivesEmptyDeviceName() {
        assertEquals("", V1Migration.plan(v1(alias = "  ")).deviceName)
        assertEquals("Pixel", V1Migration.plan(v1(alias = " Pixel ")).deviceName)
    }

    @Test fun nullMeansFreshInstall() {
        val plan = V1Migration.plan(null)
        assertTrue(plan.destinations.isEmpty())
        assertTrue(plan.assignToRules.isEmpty())
        assertFalse(plan.masterOn)
        assertNull(plan.token)
        assertEquals("", plan.deviceName)
    }

    @Test fun masterFollowsV1ServiceFlagAndBlankTokenIsNull() {
        assertFalse(V1Migration.plan(v1(serviceOn = false)).masterOn)
        assertTrue(V1Migration.plan(v1(serviceOn = true)).masterOn)
        assertNull(V1Migration.plan(v1(token = " ")).token)
    }

    @Test fun blankChatEntriesAreDropped() {
        assertEquals(listOf("1", "2"), V1Migration.splitChats(" 1,, 2 ,"))
    }

    @Test fun doneMeansNothing() {
        assertEquals(Steps(false, false, false, false), V1Migration.steps(done = true, rulesExist = true, v1Present = true))
    }

    @Test fun rulesExistWithoutV1OnlyMarksDone() {
        assertEquals(Steps(false, false, true, false), V1Migration.steps(done = false, rulesExist = true, v1Present = false))
    }

    @Test fun firstRunWithV1IsFull() {
        assertEquals(Steps(true, true, true, true), V1Migration.steps(done = false, rulesExist = false, v1Present = true))
    }

    @Test fun firstRunWithoutV1IsFreshDefaults() {
        assertEquals(Steps(true, true, true, true), V1Migration.steps(done = false, rulesExist = false, v1Present = false))
    }

    @Test fun partialRetryWithV1SkipsDbButRewritesSettings() {
        assertEquals(Steps(false, true, true, true), V1Migration.steps(done = false, rulesExist = true, v1Present = true))
    }
}
