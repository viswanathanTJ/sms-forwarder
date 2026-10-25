package com.viswa2k.smsforwarder.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Title-only placeholder; replaced by Task 13 (Rules, editor), 14 (Log) and 15 (Settings). */
@Composable
fun StubScreen(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
