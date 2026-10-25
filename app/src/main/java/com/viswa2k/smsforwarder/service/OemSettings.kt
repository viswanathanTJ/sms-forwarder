package com.viswa2k.smsforwarder.service

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import com.viswa2k.smsforwarder.R

/** Where a step's Open button can go. Pure ids so the tables are JVM-testable; [OemSettings.intents] builds the Intents. */
enum class OemTarget {
    APP_DETAILS, SAMSUNG_BATTERY, BATTERY_SAVER, IGNORE_BATTERY_OPT,
    COLOROS_STARTUP, VIVO_AUTOSTART, VIVO_BG_POWER, MIUI_AUTOSTART,
}

/** One numbered step: text is a string resource (with **bold** parts); no targets means no Open button. */
data class OemStep(@StringRes val textRes: Int, val targets: List<OemTarget>)

data class OemGuide(val brandKey: String, @StringRes val brandLabelRes: Int, val steps: List<OemStep>)

/** Per-brand "auto-start / never sleeping" guide. The caller tries each target's intents in order, then App info. */
object OemSettings {
    const val SAMSUNG = "samsung"
    const val OPPO = "oppo"
    const val VIVO = "vivo"
    const val XIAOMI = "xiaomi"
    const val OTHER = "other"

    val allBrandKeys = listOf(SAMSUNG, OPPO, VIVO, XIAOMI, OTHER)

    fun detectBrandKey(manufacturer: String): String {
        val m = manufacturer.trim().lowercase()
        return when {
            "samsung" in m -> SAMSUNG
            m == "realme" || m == "oppo" || m == "oneplus" -> OPPO
            m == "vivo" || m == "iqoo" -> VIVO
            m == "xiaomi" || m == "redmi" || m == "poco" -> XIAOMI
            else -> OTHER
        }
    }

    private val app = listOf(OemTarget.APP_DETAILS)

    private val guides: Map<String, OemGuide> = listOf(
        OemGuide(SAMSUNG, R.string.oem_brand_samsung, listOf(
            OemStep(R.string.oem_samsung_1, app),
            OemStep(R.string.oem_samsung_2, listOf(OemTarget.SAMSUNG_BATTERY, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_samsung_3, listOf(OemTarget.SAMSUNG_BATTERY, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_samsung_4, emptyList()),
        )),
        OemGuide(OPPO, R.string.oem_brand_oppo, listOf(
            OemStep(R.string.oem_oppo_1, app),
            OemStep(R.string.oem_oppo_2, listOf(OemTarget.BATTERY_SAVER, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_oppo_3, listOf(OemTarget.COLOROS_STARTUP, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_oppo_4, emptyList()),
        )),
        OemGuide(VIVO, R.string.oem_brand_vivo, listOf(
            OemStep(R.string.oem_vivo_1, app),
            OemStep(R.string.oem_vivo_2, listOf(OemTarget.VIVO_AUTOSTART, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_vivo_3, listOf(OemTarget.VIVO_BG_POWER, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_vivo_4, emptyList()),
        )),
        OemGuide(XIAOMI, R.string.oem_brand_xiaomi, listOf(
            OemStep(R.string.oem_xiaomi_1, listOf(OemTarget.MIUI_AUTOSTART, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_xiaomi_2, app),
            OemStep(R.string.oem_xiaomi_3, emptyList()),
        )),
        OemGuide(OTHER, R.string.oem_brand_other, listOf(
            OemStep(R.string.oem_other_1, app),
            OemStep(R.string.oem_other_2, listOf(OemTarget.IGNORE_BATTERY_OPT, OemTarget.APP_DETAILS)),
            OemStep(R.string.oem_other_3, emptyList()),
        )),
    ).associateBy { it.brandKey }

    fun guideForKey(brandKey: String): OemGuide = guides.getValue(if (brandKey in guides) brandKey else OTHER)

    fun guide(manufacturer: String): OemGuide = guideForKey(detectBrandKey(manufacturer))

    private fun component(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Intents for a step, in the order to try; always ends with App info so the button never dead-ends. */
    fun intents(targets: List<OemTarget>): List<Intent> =
        (targets.flatMap(::intentsFor) + appDetails()).distinctBy { it.toUri(0) }

    private fun intentsFor(t: OemTarget): List<Intent> = when (t) {
        OemTarget.APP_DETAILS -> listOf(appDetails())
        OemTarget.SAMSUNG_BATTERY -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        OemTarget.BATTERY_SAVER -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        OemTarget.IGNORE_BATTERY_OPT ->
            listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        OemTarget.COLOROS_STARTUP -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        OemTarget.VIVO_AUTOSTART -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        OemTarget.VIVO_BG_POWER -> listOf(
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        OemTarget.MIUI_AUTOSTART -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
    }

    fun appDetails(packageName: String = "com.viswa2k.smsforwarder"): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
