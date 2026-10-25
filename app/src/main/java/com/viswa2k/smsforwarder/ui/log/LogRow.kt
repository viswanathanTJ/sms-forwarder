package com.viswa2k.smsforwarder.ui.log

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.Origin
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.ui.common.StatusChip
import com.viswa2k.smsforwarder.ui.common.TypeChip
import com.viswa2k.smsforwarder.ui.common.WhatsAppChip
import java.util.Date

/** "10:41" today, "Yesterday 10:41" or "24 Oct 10:41" otherwise, in the user's 12/24 h format. */
@Composable
fun rememberTime(epochMs: Long): String {
    val context = LocalContext.current
    val yesterday = stringResource(R.string.log_yesterday)
    return remember(epochMs) {
        val time = DateFormat.getTimeFormat(context).format(Date(epochMs))
        when (dayBucket(epochMs, System.currentTimeMillis())) {
            DayBucket.TODAY -> time
            DayBucket.YESTERDAY -> "$yesterday $time"
            DayBucket.OLDER -> "${java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(Date(epochMs))} $time"
        }
    }
}

@Composable
fun NoteLine.label(): String = when (this) {
    is NoteLine.Skipped -> stringResource(
        when (reason) {
            SkipReason.PAUSED -> R.string.skip_paused
            SkipReason.SPAM -> R.string.skip_spam
            SkipReason.NO_RULE -> R.string.skip_no_rule
            SkipReason.CONTACTS_PERMISSION_MISSING -> R.string.skip_contacts_permission
            SkipReason.SOURCE_FILTERED -> R.string.skip_no_rule // unreachable: noteLine maps it to SourceFiltered
        },
    )
    is NoteLine.SourceFiltered -> text()
    is NoteLine.Routed -> text()
    NoteLine.MatchedNothingSent -> stringResource(R.string.recent_matched_nothing_sent)
}

/** One log entry (sender, time, one-line body, type + status chips, note). Shared by Home and Log. */
@Composable
fun LogRow(row: LogRowData, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val time = rememberTime(row.receivedAt)
    Column(modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.senderLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(
            row.body.replace('\n', ' '),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.origin == Origin.WHATSAPP) WhatsAppChip()
            row.type?.let { TypeChip(it) }
            StatusChip(row.state)
        }
        Text(row.note.label(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
