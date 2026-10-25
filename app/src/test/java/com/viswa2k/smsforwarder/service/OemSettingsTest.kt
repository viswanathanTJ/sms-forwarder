package com.viswa2k.smsforwarder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemSettingsTest {
    @Test fun detectsBrands() {
        val expected = mapOf(
            "samsung" to "samsung", "Samsung" to "samsung", "realme" to "oppo", "OPPO" to "oppo",
            "OnePlus" to "oppo", "vivo" to "vivo", "iQOO" to "vivo", "Xiaomi" to "xiaomi",
            "Redmi" to "xiaomi", "POCO" to "xiaomi", "Google" to "other", "" to "other",
        )
        expected.forEach { (m, key) -> assertEquals(m, key, OemSettings.detectBrandKey(m)) }
    }

    @Test fun everyBrandHasGuideWithSteps() {
        assertEquals(5, OemSettings.allBrandKeys.size)
        OemSettings.allBrandKeys.forEach { key ->
            val g = OemSettings.guideForKey(key)
            assertEquals(key, g.brandKey)
            assertTrue("$key steps", g.steps.size >= 3)
            // every step except the closing "tick Done" one has an Open target
            g.steps.dropLast(1).forEach { assertTrue("$key step", it.targets.isNotEmpty()) }
            assertTrue(g.steps.last().targets.isEmpty())
            assertEquals(g.steps.size, g.steps.map { it.textRes }.toSet().size)
        }
    }

    @Test fun stepCountsMatchBrief() {
        assertEquals(4, OemSettings.guide("samsung").steps.size)
        assertEquals(4, OemSettings.guide("realme").steps.size)
        assertEquals(4, OemSettings.guide("vivo").steps.size)
        assertEquals(3, OemSettings.guide("Redmi").steps.size)
        assertEquals(3, OemSettings.guide("Google").steps.size)
    }

    @Test fun unknownKeyFallsBackToOther() {
        assertEquals("other", OemSettings.guideForKey("nope").brandKey)
    }
}
