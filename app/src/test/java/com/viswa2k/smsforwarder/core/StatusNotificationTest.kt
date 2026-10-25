package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusNotificationTest {
    @Test fun `shown when app enabled and channel not none`() {
        assertTrue(StatusNotification.isShown(true, 1))
        assertTrue(StatusNotification.isShown(true, null))
    }

    @Test fun `hidden when app disabled or channel none`() {
        assertFalse(StatusNotification.isShown(false, 3))
        assertFalse(StatusNotification.isShown(true, 0))
    }

    @Test fun `labels`() {
        assertEquals("Shown", StatusNotification.statusLabel(true))
        assertEquals("Hidden", StatusNotification.statusLabel(false))
        assertEquals("Status notification hidden", StatusNotification.healthTitle(false))
    }
}
