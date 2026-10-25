package com.viswa2k.smsforwarder.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStepsTest {
    private val nothing = SetupSnapshot()
    private val perms = SetupSnapshot(smsOk = true, contactsOk = true)

    @Test fun `page order`() {
        assertEquals(
            listOf(OnbStep.WELCOME, OnbStep.PERMISSIONS, OnbStep.BATTERY, OnbStep.AUTOSTART, OnbStep.TELEGRAM, OnbStep.READY),
            OnboardingSteps.steps,
        )
    }

    @Test fun `permissions need sms and contacts, notifications optional`() {
        assertFalse(OnboardingSteps.canAdvance(OnbStep.PERMISSIONS, nothing))
        assertFalse(OnboardingSteps.canAdvance(OnbStep.PERMISSIONS, SetupSnapshot(smsOk = true)))
        assertFalse(OnboardingSteps.canAdvance(OnbStep.PERMISSIONS, SetupSnapshot(contactsOk = true)))
        assertTrue(OnboardingSteps.canAdvance(OnbStep.PERMISSIONS, perms))
        assertTrue(OnboardingSteps.canAdvance(OnbStep.PERMISSIONS, perms.copy(notificationsOk = false)))
    }

    @Test fun `battery, auto-start and telegram are skippable`() {
        for (s in listOf(OnbStep.WELCOME, OnbStep.BATTERY, OnbStep.AUTOSTART, OnbStep.TELEGRAM)) {
            assertTrue("$s", OnboardingSteps.canAdvance(s, nothing))
        }
    }

    @Test fun `advance moves one page only from the visible page`() {
        assertEquals(1, OnboardingSteps.advance(from = 0, current = 0, s = nothing))
        // A second tap on Welcome's button lands after the pager already moved: ignored.
        assertEquals(1, OnboardingSteps.advance(from = 0, current = 1, s = nothing))
        assertEquals(2, OnboardingSteps.advance(from = 1, current = 1, s = perms))
        assertEquals(3, OnboardingSteps.advance(from = 2, current = 2, s = nothing))
        assertEquals(5, OnboardingSteps.advance(from = 5, current = 5, s = perms))
    }

    @Test fun `advance never passes permissions without sms and contacts`() {
        assertEquals(1, OnboardingSteps.advance(from = 1, current = 1, s = nothing))
        assertEquals(1, OnboardingSteps.advance(from = 1, current = 1, s = SetupSnapshot(smsOk = true)))
        // Double tap from Welcome: the first moves to Permissions, the second (still from Welcome) does nothing.
        var page = 0
        repeat(2) { page = OnboardingSteps.advance(from = 0, current = page, s = nothing) }
        assertEquals(1, page)
        assertTrue(OnboardingSteps.at(page) != OnbStep.BATTERY)
    }

    @Test fun `back moves one page only from the visible page`() {
        assertEquals(2, OnboardingSteps.back(from = 3, current = 3))
        assertEquals(2, OnboardingSteps.back(from = 3, current = 2))
        assertEquals(0, OnboardingSteps.back(from = 0, current = 0))
    }

    @Test fun `completion per step`() {
        val all = SetupSnapshot(smsOk = true, contactsOk = true, notificationsOk = true, batteryOk = true, oemConfirmed = true, telegramReady = true)
        for (s in OnboardingSteps.checklist) {
            assertFalse("$s", OnboardingSteps.isComplete(s, nothing))
            assertTrue("$s", OnboardingSteps.isComplete(s, all))
        }
        assertTrue(OnboardingSteps.isComplete(OnbStep.PERMISSIONS, perms))
        assertTrue(OnboardingSteps.isComplete(OnbStep.BATTERY, SetupSnapshot(batteryOk = true)))
        assertTrue(OnboardingSteps.isComplete(OnbStep.AUTOSTART, SetupSnapshot(oemConfirmed = true)))
        assertTrue(OnboardingSteps.isComplete(OnbStep.TELEGRAM, SetupSnapshot(telegramReady = true)))
    }

    @Test fun `ready checklist covers the setup steps`() {
        assertEquals(listOf(OnbStep.PERMISSIONS, OnbStep.BATTERY, OnbStep.AUTOSTART, OnbStep.TELEGRAM), OnboardingSteps.checklist)
    }

    @Test fun `page index moves within bounds`() {
        assertEquals(1, OnboardingSteps.next(0))
        assertEquals(5, OnboardingSteps.next(5))
        assertEquals(0, OnboardingSteps.previous(0))
        assertEquals(2, OnboardingSteps.previous(3))
        assertEquals(OnbStep.READY, OnboardingSteps.at(99))
        assertEquals(OnbStep.WELCOME, OnboardingSteps.at(-1))
    }

    @Test fun `permission chip`() {
        assertEquals(PermStatus.GRANTED, OnboardingSteps.permStatus(granted = true, required = true))
        assertEquals(PermStatus.GRANTED, OnboardingSteps.permStatus(granted = true, required = false))
        assertEquals(PermStatus.NEEDED, OnboardingSteps.permStatus(granted = false, required = true))
        assertEquals(PermStatus.OPTIONAL, OnboardingSteps.permStatus(granted = false, required = false))
    }

    @Test fun `allow requests only while a required permission is missing`() {
        assertEquals(PermAction.NONE, OnboardingSteps.permAction(missingRequired = false, askedBefore = false))
        assertEquals(PermAction.NONE, OnboardingSteps.permAction(missingRequired = false, askedBefore = true))
        assertEquals(PermAction.REQUEST, OnboardingSteps.permAction(missingRequired = true, askedBefore = false))
        // After a refusal Android may not show the dialog again: send the user to App info instead.
        assertEquals(PermAction.OPEN_SETTINGS, OnboardingSteps.permAction(missingRequired = true, askedBefore = true))
    }

    @Test fun `snapshot from health ids`() {
        val s = SetupSnapshot.from(mapOf("sms" to true, "contacts" to false, "battery" to true), notificationsOk = false, oemConfirmed = true, telegramReady = false)
        assertEquals(SetupSnapshot(smsOk = true, contactsOk = false, notificationsOk = false, batteryOk = true, oemConfirmed = true), s)
    }
}
