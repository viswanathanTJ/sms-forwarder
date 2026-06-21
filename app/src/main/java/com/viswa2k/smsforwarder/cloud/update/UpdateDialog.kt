package com.viswa2k.smsforwarder.cloud.update

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.BuildConfig

/**
 * On first composition, checks GitHub Releases for a newer version and, if found,
 * shows a dismissible dialog offering to download + install it. No-op when the
 * app is up to date or the check fails (offline, etc.).
 */
@Composable
fun UpdateDialogHost() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        info = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
    }

    val update = info
    if (update != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
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
                    dismissed = true
                    UpdateInstaller.startUpdate(context, update)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text("Later") }
            },
        )
    }
}
