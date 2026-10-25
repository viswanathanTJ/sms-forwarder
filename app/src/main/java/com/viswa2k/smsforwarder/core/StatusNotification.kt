package com.viswa2k.smsforwarder.core

/** Pure rules for the foreground-service status notification, which is optional for the owner. */
object StatusNotification {
    /** Visible when the app's notifications are on and the channel is not switched off (null = not created yet). */
    fun isShown(appEnabled: Boolean, channelImportance: Int?): Boolean =
        appEnabled && channelImportance != IMPORTANCE_NONE

    fun statusLabel(shown: Boolean): String = if (shown) "Shown" else "Hidden"

    fun healthTitle(shown: Boolean): String = if (shown) "Status notification shown" else "Status notification hidden"

    /** Matches android.app.NotificationManager.IMPORTANCE_NONE (kept literal so this stays JVM-testable). */
    private const val IMPORTANCE_NONE = 0
}
