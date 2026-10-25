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
     * on but Notification access is missing, so nothing is received). [enabled] null = not loaded (Off); [access] null = not checked yet (plain On, never a false "Access needed").
     */
    fun whatsApp(enabled: Boolean?, types: Set<MessageType>, access: Boolean?): RowText = when {
        enabled != true -> RowText(R.string.home_source_wa_off)
        access == false -> RowText(R.string.home_source_wa_access, tone = RowTone.PROBLEM)
        else -> SettingsSummary.whatsAppTypes(types, R.string.home_source_wa_on_only, R.string.home_source_wa_on, RowTone.OK)
    }

    /** Settings does the same: switching on without Notification access opens the system screen. */
    fun shouldOpenAccess(turningOn: Boolean, access: Boolean): Boolean = turningOn && !access
}
