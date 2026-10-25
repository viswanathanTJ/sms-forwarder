package com.viswa2k.smsforwarder.service

import android.Manifest
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
import kotlinx.coroutines.flow.first

data class HealthItem(val id: String, val title: String, val ok: Boolean, val fix: Intent?)

/** Point-in-time checks of everything forwarding depends on; also feeds [ProblemAlerter]. */
class HealthChecker(context: Context) {
    private val context = context.applicationContext

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun smsOk() = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS) &&
        granted(Manifest.permission.SEND_SMS)

    fun contactsOk() = granted(Manifest.permission.READ_CONTACTS)

    fun notificationsOk(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS)
        else NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryOk(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun classifierOk(): Boolean = AppGraph.from(context).classifierLoaded

    /** Problems among the five local checks (destination failures are added by [ProblemAlerter]). */
    fun problems(): Set<Problem> = buildSet {
        if (!smsOk()) add(Problem.SMS_PERMISSION)
        if (!contactsOk()) add(Problem.CONTACTS_PERMISSION)
        if (!notificationsOk()) add(Problem.NOTIFICATIONS)
        if (!batteryOk()) add(Problem.BATTERY_OPTIMIZED)
        if (!classifierOk()) add(Problem.CLASSIFIER)
    }

    private fun appSettings(action: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS) =
        Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun items(): List<HealthItem> {
        val oemConfirmed = AppGraph.from(context).settings.setupConfirmedOem.first()
        return listOf(
            HealthItem("sms", "SMS permission (receive, read, send)", smsOk(), appSettings()),
            HealthItem("contacts", "Contacts permission", contactsOk(), appSettings()),
            HealthItem(
                "notif", "Notifications", notificationsOk(),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
            HealthItem(
                "battery", "Battery unrestricted", batteryOk(),
                appSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            ),
            HealthItem("oem", "Auto-start allowed (${Build.MANUFACTURER})", oemConfirmed, null),
            HealthItem("classifier", "Message filter loaded", classifierOk(), null),
        )
    }
}
