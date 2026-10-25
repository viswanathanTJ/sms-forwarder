package com.viswa2k.smsforwarder.ui.nav

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.viswa2k.smsforwarder.R

private const val PREFS = "app_prefs"
private const val KEY = "privacy_accepted"

/** One-time privacy notice; shown after onboarding (never over it) until acknowledged. */
@Composable
fun PrivacyNotice() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var accepted by remember { mutableStateOf(prefs.getBoolean(KEY, false)) }
    if (accepted) return
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.privacy_policy_title)) },
        text = { Text(stringResource(R.string.privacy_policy_summary)) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean(KEY, true).apply()
                accepted = true
            }) { Text(stringResource(R.string.privacy_policy_accept)) }
        },
    )
}
