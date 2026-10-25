package com.viswa2k.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.viswa2k.smsforwarder.BuildConfig
import com.viswa2k.smsforwarder.R

/** Temporary home screen (app name + version); replaced by the real UI shell in Task 12. */
@Composable
fun PlaceholderScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    }
}
