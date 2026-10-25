package com.viswa2k.smsforwarder.ui.settings

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.SourceFilter
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.service.OemSettings

/** Colour of a row's supporting line: neutral grey, green (set up / all good) or red (needs attention). */
enum class RowTone { NEUTRAL, OK, PROBLEM }

/**
 * A Settings row's supporting line as resource ids, so the decision stays JVM-testable and the text stays in
 * resources. Exactly one of [res] / [plural] is set; [argRes] (when set) is resolved and passed before [args].
 */
data class RowText(
    @param:StringRes val res: Int = 0,
    @param:PluralsRes val plural: Int = 0,
    val count: Int = 0,
    val args: List<String> = emptyList(),
    @param:StringRes val argRes: Int = 0,
    val tone: RowTone = RowTone.NEUTRAL,
)

/** Builds every Settings root row's live status line from state (pure; see SettingsSummaryTest). */
object SettingsSummary {
    fun telegram(hasToken: Boolean, chats: Int): RowText = when {
        !hasToken && chats == 0 -> RowText(R.string.settings_row_not_set_up)
        !hasToken -> RowText(plural = R.plurals.settings_row_tg_no_token, count = chats, tone = RowTone.PROBLEM)
        chats == 0 -> RowText(R.string.settings_row_tg_no_chats, tone = RowTone.PROBLEM)
        else -> RowText(plural = R.plurals.settings_row_tg_chats, count = chats, tone = RowTone.OK)
    }

    fun sms(numbers: Int): RowText =
        if (numbers == 0) RowText(R.string.settings_row_none) else RowText(plural = R.plurals.settings_row_numbers, count = numbers)

    /** [whatsAppOn] null = not loaded yet (shown as SMS only, like the off state). */
    fun sources(whatsAppOn: Boolean?, types: Set<MessageType>): RowText {
        if (whatsAppOn != true) return RowText(R.string.settings_row_sources_sms_only)
        val labels = SourceFilter.SELECTABLE.filter { it in types }.map { it.label }
        return if (labels.size == 1) RowText(R.string.settings_row_sources_wa_only, args = labels)
        else RowText(R.string.settings_row_sources_wa, args = listOf(labels.joinToString(", ")))
    }

    fun test(): RowText = RowText(R.string.settings_row_test)

    /** Empty = not checked yet. Informational items (the optional status notice) never count as problems. */
    fun health(items: List<HealthItem>): RowText {
        if (items.isEmpty()) return RowText(R.string.health_checking)
        val problems = items.count { !it.ok && !it.informational }
        return if (problems == 0) RowText(R.string.settings_row_all_good, tone = RowTone.OK)
        else RowText(plural = R.plurals.settings_row_attention, count = problems, tone = RowTone.PROBLEM)
    }

    fun autoStart(manufacturer: String, confirmed: Boolean): RowText = RowText(
        if (confirmed) R.string.settings_row_oem_done else R.string.settings_row_oem_not_done,
        argRes = OemSettings.guide(manufacturer).brandLabelRes,
        tone = if (confirmed) RowTone.OK else RowTone.PROBLEM,
    )

    fun notification(shown: Boolean): RowText = RowText(if (shown) R.string.settings_row_shown else R.string.settings_row_hidden)

    fun deviceName(name: String, model: String): RowText = RowText(R.string.settings_row_plain, args = listOf(name.trim().ifBlank { model }))

    fun pin(set: Boolean): RowText = if (set) RowText(R.string.settings_row_on, tone = RowTone.OK) else RowText(R.string.settings_row_off)

    fun backup(): RowText = RowText(R.string.settings_row_backup)

    fun about(version: String, update: UpdateStatus): RowText = when (update) {
        UpdateStatus.Idle -> RowText(R.string.settings_row_about_check, args = listOf(version))
        UpdateStatus.Checking -> RowText(R.string.settings_row_about_checking, args = listOf(version))
        UpdateStatus.UpToDate -> RowText(R.string.settings_row_about_latest, args = listOf(version))
        UpdateStatus.Failed -> RowText(R.string.settings_row_about_failed, args = listOf(version))
        is UpdateStatus.Available -> RowText(R.string.settings_row_about_available, args = listOf(version), tone = RowTone.OK)
    }

    /** Every root row at once (what SettingsViewModel.summaries publishes). */
    fun rows(
        s: SettingsState,
        health: List<HealthItem>,
        whatsAppOn: Boolean?,
        whatsAppTypes: Set<MessageType>,
        update: UpdateStatus,
        notificationShown: Boolean,
        manufacturer: String,
        model: String,
        version: String,
    ): Map<SettingsSection, RowText> = mapOf(
        SettingsSection.TELEGRAM to telegram(s.hasToken, s.telegram.size),
        SettingsSection.SMS to sms(s.sms.size),
        SettingsSection.SOURCES to sources(whatsAppOn, whatsAppTypes),
        SettingsSection.TEST to test(),
        SettingsSection.HEALTH to health(health),
        SettingsSection.AUTOSTART to autoStart(manufacturer, s.oemConfirmed),
        SettingsSection.NOTIFICATION to notification(notificationShown),
        SettingsSection.DEVICE to deviceName(s.deviceName, model),
        SettingsSection.PIN to pin(s.pinSet),
        SettingsSection.BACKUP to backup(),
        SettingsSection.ABOUT to about(version, update),
    )
}
