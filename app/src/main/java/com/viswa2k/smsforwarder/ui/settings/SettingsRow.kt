package com.viswa2k.smsforwarder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * A roomy two-line list row (M3 list-item anatomy: tinted leading icon, headline, supporting status that may
 * wrap to two lines, trailing chevron). Min height 72 dp.
 */
@Composable
internal fun SettingsRow(icon: ImageVector, tint: Tone, title: String, summary: RowText?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tint.soft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.fg, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Blank (not absent) until loaded, so rows keep their height.
            Text(
                summary?.let { rowText(it) } ?: " ",
                color = (summary?.tone ?: RowTone.NEUTRAL).color(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
