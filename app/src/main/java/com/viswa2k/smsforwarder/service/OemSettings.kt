package com.viswa2k.smsforwarder.service

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Vendor "auto-start / never sleeping" screens. The caller tries each intent until one resolves. */
object OemSettings {
    private fun component(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun intents(manufacturer: String): List<Intent> {
        val vendor = manufacturer.lowercase()
        val specific = when {
            vendor.contains("samsung") -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            vendor in setOf("realme", "oppo", "oneplus") -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            vendor in setOf("xiaomi", "redmi", "poco") -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            vendor in setOf("vivo", "iqoo") -> listOf(
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            else -> emptyList()
        }
        return specific + appDetails()
    }

    fun appDetails(packageName: String = "com.viswa2k.smsforwarder"): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hint(manufacturer: String): String {
        val vendor = manufacturer.lowercase()
        return when {
            vendor.contains("samsung") ->
                "Open Battery, then \"Never sleeping apps\", and add SMS Forwarder."
            vendor in setOf("realme", "oppo", "oneplus") ->
                "Open the startup manager and allow SMS Forwarder to auto-start and run in the background."
            vendor in setOf("xiaomi", "redmi", "poco") ->
                "Open Autostart and switch SMS Forwarder on; set battery saver to \"No restrictions\"."
            vendor in setOf("vivo", "iqoo") ->
                "Allow SMS Forwarder to auto-start and run in the background (High background power consumption)."
            else -> "Open the app settings, then Battery, and set it to Unrestricted."
        }
    }
}
