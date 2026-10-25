package com.viswa2k.smsforwarder.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionsTest {
    @Test fun directionForEveryTabPair() {
        for ((i, from) in Routes.TABS.withIndex()) for ((j, to) in Routes.TABS.withIndex()) {
            val expected = when {
                j > i -> TabDirection.RIGHT
                j < i -> TabDirection.LEFT
                else -> TabDirection.NONE
            }
            assertEquals("$from -> $to", expected, tabDirection(from, to))
        }
    }

    @Test fun tabOrderMatchesBottomBar() {
        assertEquals(listOf("home", "rules", "log", "settings"), Routes.TABS)
    }

    @Test fun unknownOrNullRoutesAreNone() {
        assertEquals(TabDirection.NONE, tabDirection(Routes.HOME, "rules/5"))
        assertEquals(TabDirection.NONE, tabDirection("rules/5", Routes.LOG))
        assertEquals(TabDirection.NONE, tabDirection(Routes.HOME, Routes.RULE_EDIT))
        assertEquals(TabDirection.NONE, tabDirection(Routes.RULE_EDIT, Routes.SETTINGS))
        assertEquals(TabDirection.NONE, tabDirection(null, Routes.LOG))
        assertEquals(TabDirection.NONE, tabDirection(Routes.LOG, null))
        assertEquals(TabDirection.NONE, tabDirection(Routes.ONBOARDING, Routes.HOME))
    }

    @Test fun allDurationsAtMost250() {
        val all = listOf(
            Transitions.TAB_MS, Transitions.TAB_EXIT_FADE_MS, Transitions.PUSH_MS,
            Transitions.PUSH_UNDER_FADE_MS, Transitions.FADE_MS, Transitions.CONTENT_MS,
        )
        assertTrue(all.all { it in 1..250 })
    }
}
