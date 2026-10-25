package com.viswa2k.smsforwarder.ui.settings

import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryTest {
    @Test fun `telegram empty, configured and half-configured`() {
        assertEquals(RowText(R.string.settings_row_not_set_up), SettingsSummary.telegram(hasToken = false, chats = 0))
        assertEquals(
            RowText(plural = R.plurals.settings_row_tg_chats, count = 2, tone = RowTone.OK),
            SettingsSummary.telegram(hasToken = true, chats = 2),
        )
        assertEquals(RowText(R.string.settings_row_tg_no_chats, tone = RowTone.PROBLEM), SettingsSummary.telegram(true, 0))
        assertEquals(
            RowText(plural = R.plurals.settings_row_tg_no_token, count = 1, tone = RowTone.PROBLEM),
            SettingsSummary.telegram(false, 1),
        )
    }

    @Test fun `sms numbers`() {
        assertEquals(RowText(R.string.settings_row_none), SettingsSummary.sms(0))
        assertEquals(RowText(plural = R.plurals.settings_row_numbers, count = 1), SettingsSummary.sms(1))
        assertEquals(RowText(plural = R.plurals.settings_row_numbers, count = 3), SettingsSummary.sms(3))
    }

    @Test fun `sources sms only, whatsapp with one type, whatsapp with several types`() {
        val sms = RowText(R.string.settings_row_sources_sms_only)
        assertEquals(sms, SettingsSummary.sources(null, setOf(MessageType.OTP)))
        assertEquals(sms, SettingsSummary.sources(false, setOf(MessageType.OTP)))
        assertEquals(
            RowText(R.string.settings_row_sources_wa_only, args = listOf("OTP")),
            SettingsSummary.sources(true, setOf(MessageType.OTP)),
        )
        // Listed in chip order, whatever order the set has.
        assertEquals(
            RowText(R.string.settings_row_sources_wa, args = listOf("${MessageType.OTP.label}, ${MessageType.TRANSACTION.label}")),
            SettingsSummary.sources(true, setOf(MessageType.TRANSACTION, MessageType.OTP)),
        )
    }

    @Test fun `health counts only real problems`() {
        assertEquals(RowText(R.string.health_checking), SettingsSummary.health(emptyList()))
        val ok = HealthItem("sms", "SMS", ok = true, fix = null)
        val hiddenNotice = HealthItem("notif", "Notice hidden", ok = false, fix = null, informational = true)
        assertEquals(RowText(R.string.settings_row_all_good, tone = RowTone.OK), SettingsSummary.health(listOf(ok, hiddenNotice)))
        val bad = listOf(ok, hiddenNotice, HealthItem("battery", "Battery", false, null), HealthItem("oem", "Auto", false, null))
        assertEquals(
            RowText(plural = R.plurals.settings_row_attention, count = 2, tone = RowTone.PROBLEM),
            SettingsSummary.health(bad),
        )
    }

    @Test fun `auto-start shows the brand and done state`() {
        assertEquals(
            RowText(R.string.settings_row_oem_done, argRes = R.string.oem_brand_samsung, tone = RowTone.OK),
            SettingsSummary.autoStart("samsung", confirmed = true),
        )
        assertEquals(
            RowText(R.string.settings_row_oem_not_done, argRes = R.string.oem_brand_xiaomi, tone = RowTone.PROBLEM),
            SettingsSummary.autoStart("Xiaomi", confirmed = false),
        )
    }

    @Test fun `notification, device, pin and static rows`() {
        assertEquals(RowText(R.string.settings_row_shown), SettingsSummary.notification(true))
        assertEquals(RowText(R.string.settings_row_hidden), SettingsSummary.notification(false))
        assertEquals(RowText(R.string.settings_row_plain, args = listOf("Shop phone")), SettingsSummary.deviceName(" Shop phone ", "SM-S921B"))
        assertEquals(RowText(R.string.settings_row_plain, args = listOf("SM-S921B")), SettingsSummary.deviceName("  ", "SM-S921B"))
        assertEquals(RowText(R.string.settings_row_on, tone = RowTone.OK), SettingsSummary.pin(true))
        assertEquals(RowText(R.string.settings_row_off), SettingsSummary.pin(false))
        assertEquals(RowText(R.string.settings_row_test), SettingsSummary.test())
        assertEquals(RowText(R.string.settings_row_backup), SettingsSummary.backup())
    }

    @Test fun `about follows the update check`() {
        assertEquals(RowText(R.string.settings_row_about_check, args = listOf("2.0.0")), SettingsSummary.about("2.0.0", UpdateStatus.Idle))
        assertEquals(RowText(R.string.settings_row_about_checking, args = listOf("2.0.0")), SettingsSummary.about("2.0.0", UpdateStatus.Checking))
        assertEquals(RowText(R.string.settings_row_about_latest, args = listOf("2.0.0")), SettingsSummary.about("2.0.0", UpdateStatus.UpToDate))
        assertEquals(RowText(R.string.settings_row_about_failed, args = listOf("2.0.0")), SettingsSummary.about("2.0.0", UpdateStatus.Failed))
        assertEquals(
            RowText(R.string.settings_row_about_available, args = listOf("2.0.0"), tone = RowTone.OK),
            SettingsSummary.about("2.0.0", UpdateStatus.Available(UpdateInfo("2.1.0", "", "", ""))),
        )
    }

    @Test fun `rows covers every section in order`() {
        val rows = SettingsSummary.rows(
            SettingsState(hasToken = true, deviceName = "", pinSet = true, oemConfirmed = false, loaded = true),
            emptyList(), false, setOf(MessageType.OTP), UpdateStatus.Idle, notificationShown = false,
            manufacturer = "samsung", model = "SM-S921B", version = "2.0.0",
        )
        assertEquals(SettingsSection.entries.toList(), rows.keys.toList())
        assertEquals(RowText(R.string.settings_row_tg_no_chats, tone = RowTone.PROBLEM), rows[SettingsSection.TELEGRAM])
        assertEquals(RowText(R.string.settings_row_hidden), rows[SettingsSection.NOTIFICATION])
        assertEquals(RowText(R.string.settings_row_plain, args = listOf("SM-S921B")), rows[SettingsSection.DEVICE])
        assertEquals(RowText(R.string.settings_row_on, tone = RowTone.OK), rows[SettingsSection.PIN])
    }
}
