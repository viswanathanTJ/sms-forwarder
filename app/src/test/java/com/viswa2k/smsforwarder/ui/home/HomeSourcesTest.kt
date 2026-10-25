package com.viswa2k.smsforwarder.ui.home

import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.ui.settings.RowText
import com.viswa2k.smsforwarder.ui.settings.RowTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSourcesTest {
    private val otp = setOf(MessageType.OTP)

    @Test fun `off when disabled or not loaded, whatever access says`() {
        val off = RowText(R.string.home_source_wa_off)
        assertEquals(off, HomeSources.whatsApp(false, otp, access = true))
        assertEquals(off, HomeSources.whatsApp(false, otp, access = false))
        assertEquals(off, HomeSources.whatsApp(null, otp, access = true))
    }

    @Test fun `on lists the types in chip order`() {
        assertEquals(
            RowText(R.string.home_source_wa_on_only, args = listOf("OTP"), tone = RowTone.OK),
            HomeSources.whatsApp(true, otp, access = true),
        )
        assertEquals(
            RowText(R.string.home_source_wa_on, args = listOf("${MessageType.OTP.label}, ${MessageType.TRANSACTION.label}"), tone = RowTone.OK),
            HomeSources.whatsApp(true, setOf(MessageType.TRANSACTION, MessageType.OTP), access = true),
        )
    }

    @Test fun `unknown access shows plain On, never Access needed`() {
        assertEquals(
            RowText(R.string.home_source_wa_on_only, args = listOf("OTP"), tone = RowTone.OK),
            HomeSources.whatsApp(true, otp, access = null),
        )
    }

    @Test fun `access needed when on without notification access`() {
        assertEquals(
            RowText(R.string.home_source_wa_access, tone = RowTone.PROBLEM),
            HomeSources.whatsApp(true, otp, access = false),
        )
    }

    @Test fun `access screen opens only when turning on without access`() {
        assertTrue(HomeSources.shouldOpenAccess(turningOn = true, access = false))
        assertFalse(HomeSources.shouldOpenAccess(turningOn = true, access = true))
        assertFalse(HomeSources.shouldOpenAccess(turningOn = false, access = false))
    }
}
