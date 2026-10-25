package com.viswa2k.smsforwarder.ui.home

import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.ui.settings.RowText
import com.viswa2k.smsforwarder.ui.settings.RowTone
import com.viswa2k.smsforwarder.ui.settings.SettingsSummary

/** Home's "Sources" strip logic (pure; see HomeSourcesTest). Type labels come from [SettingsSummary]. */
object HomeSources {
    /**
     * The WhatsApp chip: Off (neutral), On with its types (OK) or Access needed (PROBLEM = amber: the toggle is
     * on but Notification access is missing, so nothing is received). [enabled] null = not loaded (Off).
     */
    fun whatsApp(enabled: Boolean?, types: Set<MessageType>, access: Boolean): RowText = when {
        enabled != true -> RowText(R.string.home_source_wa_off)
        !access -> RowText(R.string.home_source_wa_access, tone = RowTone.PROBLEM)
        else -> {
            val labels = SettingsSummary.whatsAppLabels(types)
            if (labels.size == 1) RowText(R.string.home_source_wa_on_only, args = labels, tone = RowTone.OK)
            else RowText(R.string.home_source_wa_on, args = listOf(labels.joinToString(", ")), tone = RowTone.OK)
        }
    }

    /** Settings does the same: switching on without Notification access opens the system screen. */
    fun shouldOpenAccess(turningOn: Boolean, access: Boolean): Boolean = turningOn && !access
}
