package com.viswa2k.smsforwarder.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.data.db.OutboxEntity
import com.viswa2k.smsforwarder.ui.common.StatusChip
import com.viswa2k.smsforwarder.ui.common.TypeChip

/** Details of one entry: full body (masked as stored), what was detected, per-destination status/error, Resend for failures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailSheet(row: LogRowData, onResend: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.sender, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(rememberTime(row.receivedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.type?.let { TypeChip(it) }
                StatusChip(row.state)
            }
            Text(row.note.label(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

            Label(R.string.log_detail_message)
            Text(row.body, fontSize = 14.sp)
            if (row.masked) {
                Text(stringResource(R.string.log_detail_masked), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (row.extracted.isNotBlank()) {
                Label(R.string.log_detail_extracted)
                Text(row.extracted, fontSize = 14.sp)
            }

            Label(R.string.log_detail_destinations)
            if (row.masked && row.sends.any { it.state == OutboxEntity.STATE_FAILED }) {
                Text(stringResource(R.string.log_resend_masked), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
            }
            if (row.sends.isEmpty()) {
                Text(stringResource(R.string.log_detail_no_sends), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            row.sends.forEachIndexed { index, send ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(send.destination ?: stringResource(R.string.log_detail_deleted_destination), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(
                                when (send.state) {
                                    OutboxEntity.STATE_SENT -> R.string.log_send_sent
                                    OutboxEntity.STATE_FAILED -> R.string.log_send_failed
                                    else -> R.string.log_send_retrying
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                        )
                        if (!send.error.isNullOrBlank()) {
                            Text(send.error, color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp)
                        }
                    }
                    if (canResend(send.state, row.masked)) {
                        OutlinedButton(onClick = { onResend(send.outboxId) }) { Text(stringResource(R.string.log_resend)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(res: Int) = Text(
    stringResource(res).uppercase(),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
    modifier = Modifier.padding(top = 6.dp),
)
