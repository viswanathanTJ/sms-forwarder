package com.viswa2k.smsforwarder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme
import com.viswa2k.smsforwarder.ui.theme.Tone

/** One Settings sub-page; [key] is the `settings/{section}` route argument. */
enum class SettingsSection(val key: String, @param:StringRes val title: Int) {
    TELEGRAM("telegram", R.string.settings_row_telegram),
    SMS("sms", R.string.settings_row_sms),
    SOURCES("sources", R.string.settings_row_sources),
    TEST("test", R.string.settings_row_test_title),
    HEALTH("health", R.string.settings_row_health),
    AUTOSTART("autostart", R.string.settings_row_autostart),
    NOTIFICATION("notification", R.string.settings_row_notification),
    DEVICE("device", R.string.settings_row_device),
    PIN("pin", R.string.settings_row_pin),
    BACKUP("backup", R.string.settings_row_backup_title),
    ABOUT("about", R.string.settings_row_about);

    companion object {
        fun fromKey(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}

/** Resolves a [RowText] from resources. */
@Composable
fun rowText(t: RowText): String {
    val args: List<Any> = (if (t.argRes != 0) listOf(stringResource(t.argRes)) else emptyList<Any>()) + t.args
    return when {
        t.plural != 0 -> pluralStringResource(t.plural, t.count, t.count, *args.toTypedArray())
        t.res != 0 -> stringResource(t.res, *args.toTypedArray())
        else -> ""
    }
}

@Composable
private fun RowTone.color() = when (this) {
    RowTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    RowTone.OK -> ForwarderTheme.extra.ok.fg
    RowTone.PROBLEM -> MaterialTheme.colorScheme.error
}

/**
 * A compact two-line list row (M3 list-item anatomy: tinted leading icon, headline, one-line supporting
 * status, trailing chevron). Denser than `ListItem`'s 72 dp so the whole root fits one phone screen.
 */
@Composable
internal fun SettingsRow(icon: ImageVector, tint: Tone, title: String, summary: RowText?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.soft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.fg, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp), maxLines = 1)
            // Blank (not absent) until loaded, so rows keep their height.
            Text(
                summary?.let { rowText(it) } ?: " ",
                color = (summary?.tone ?: RowTone.NEUTRAL).color(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
