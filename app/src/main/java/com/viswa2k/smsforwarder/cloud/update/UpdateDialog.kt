package com.viswa2k.smsforwarder.cloud.update

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.dataStore

/**
 * Refreshes the update check on launch and, if a newer version is available
 * (from the persisted [UpdateManager] state), shows a dismissible prompt.
 * The persistent indicator lives in Settings → About; this is just the launch nudge.
 */
@Composable
fun UpdateDialogHost() {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context.dataStore) }
    val update by UpdateManager.available(prefs).collectAsState(initial = null)
    var dismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { UpdateManager.refresh(prefs) } }

    val u = update
    if (u != null && !dismissed) {
        UpdatePromptDialog(update = u, onDismiss = { dismissed = true })
    }
}

/** The "update available" dialog, reused by the launch nudge and the Settings indicator. */
@Composable
fun UpdatePromptDialog(update: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available: ${update.version}") },
        text = {
            Text(
                text = update.notes.ifBlank { "A new version is available." },
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                UpdateInstaller.startUpdate(context, update)
            }) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}
