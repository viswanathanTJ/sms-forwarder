package com.viswa2k.smsforwarder.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.Problem
import com.viswa2k.smsforwarder.core.StatusNotification
import com.viswa2k.smsforwarder.pipeline.WhatsAppNotificationListener
import kotlinx.coroutines.flow.first

/** [fallbacks] are tried in order when [fix] does not resolve on this ROM. */
data class HealthItem(val id: String, val title: String, val ok: Boolean, val fix: Intent?, val fallbacks: List<Intent> = emptyList(),
    /** Shown neutral (grey), never red, and not counted as needing attention. */
    val informational: Boolean = false,
)

/** Point-in-time checks of everything forwarding depends on; also feeds [ProblemAlerter]. */
class HealthChecker(context: Context) {
    private val context = context.applicationContext

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun smsOk() = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS) &&
        granted(Manifest.permission.SEND_SMS)

    fun contactsOk() = granted(Manifest.permission.READ_CONTACTS)

    /** The status notification is visible: app notifications on and the channel not switched off. */
    fun statusNotificationShown(): Boolean {
        val nm = NotificationManagerCompat.from(context)
        return StatusNotification.isShown(
            nm.areNotificationsEnabled(),
            nm.getNotificationChannelCompat(ForwarderService.CHANNEL_ID)?.importance,
        )
    }

    fun batteryOk(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun classifierOk(): Boolean = AppGraph.from(context).classifierLoaded

    /** This app is in the system's Notification access list (needed by the WhatsApp source). */
    fun notificationAccessOk(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Problems among the local checks (destination failures are added by [ProblemAlerter]). */
    suspend fun problems(): Set<Problem> = buildSet {
        if (AppGraph.from(context).settings.whatsAppEnabled.first() && !notificationAccessOk()) add(Problem.NOTIF_ACCESS)
        if (!smsOk()) add(Problem.SMS_PERMISSION)
        if (!contactsOk()) add(Problem.CONTACTS_PERMISSION)
        if (!batteryOk()) add(Problem.BATTERY_OPTIMIZED)
        if (!classifierOk()) add(Problem.CLASSIFIER)
    }

    private fun appSettings(action: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS) =
        Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun items(): List<HealthItem> {
        val settings = AppGraph.from(context).settings
        val oemConfirmed = settings.setupConfirmedOem.first()
        val whatsApp = settings.whatsAppEnabled.first()
        return listOfNotNull(
            HealthItem("sms", "SMS permission (receive, read, send)", smsOk(), appSettings()),
            HealthItem("contacts", "Contacts permission", contactsOk(), appSettings()),
            statusNotificationShown().let { shown ->
                val intents = statusNotificationIntents(context)
                HealthItem("notif", StatusNotification.healthTitle(shown), shown, intents.first(), intents.drop(1), informational = true)
            },
            HealthItem(
                "battery", "Battery unrestricted", batteryOk(),
                appSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            ),
            HealthItem("oem", "Auto-start allowed (${Build.MANUFACTURER})", oemConfirmed, null),
            HealthItem("classifier", "Message filter loaded", classifierOk(), null),
            // Only while the WhatsApp source is on: without access it silently receives nothing.
            if (whatsApp) {
                val intents = notificationAccessIntents(context)
                HealthItem("notif_access", "WhatsApp notification access", notificationAccessOk(), intents.first(), intents.drop(1))
            } else null,
        )
    }

    companion object {
        /**
         * Status-notification channel settings, then the app's notification settings, then App info. Creates the
         * channel first when it does not exist yet, so the channel page is never empty.
         */
        fun statusNotificationIntents(context: Context): List<Intent> = run {
            ForwarderService.ensureChannel(context)
            listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ForwarderService.CHANNEL_ID),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        /** The system Notification access screen: on API 30+ this app's own page first, then the full list. */
        fun notificationAccessIntents(context: Context): List<Intent> = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, WhatsAppNotificationListener::class.java).flattenToString(),
                )
            } else null,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
