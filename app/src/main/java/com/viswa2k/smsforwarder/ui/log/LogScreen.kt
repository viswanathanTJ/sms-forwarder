package com.viswa2k.smsforwarder.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val vm: LogViewModel = viewModel(factory = LogViewModel.factory(AppGraph.from(LocalContext.current)))
    val state by vm.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val visible = state.visible
    // Looked up live so the sheet reflects a resend, and closes itself if the row is cleared or pruned.
    val open = openId?.let { id -> state.all.firstOrNull { it.id == id } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.title_log), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(enabled = state.all.isNotEmpty(), onClick = { confirmClear = true }) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.log_clear))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LogFilter.entries) { f ->
                FilterChip(
                    selected = state.filter == f,
                    onClick = { vm.setFilter(f) },
                    label = {
                        Text(
                            stringResource(
                                when (f) {
                                    LogFilter.ALL -> R.string.log_filter_all
                                    LogFilter.FORWARDED -> R.string.log_filter_forwarded
                                    LogFilter.SKIPPED -> R.string.log_filter_skipped
                                    LogFilter.FAILED_RETRYING -> R.string.log_filter_failed_retrying
                                },
                            ),
                        )
                    },
                )
            }
        }
        if (visible.isEmpty()) {
            if (state.loaded) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        stringResource(if (state.all.isEmpty()) R.string.log_empty else R.string.log_empty_filtered),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().weight(1f, fill = false),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    items(visible, key = { it.id }) { row ->
                        Column {
                            LogRow(row, Modifier.padding(vertical = 10.dp), onClick = { openId = row.id })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    open?.let { LogDetailSheet(it, onResend = vm::resend, onDismiss = { openId = null }) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = { Text(stringResource(R.string.log_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear()
                }) { Text(stringResource(R.string.log_clear_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.log_cancel)) } },
        )
    }
}
