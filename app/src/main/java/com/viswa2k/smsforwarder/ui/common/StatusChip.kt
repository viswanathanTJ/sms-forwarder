package com.viswa2k.smsforwarder.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme
import com.viswa2k.smsforwarder.ui.theme.Tone

/** What happened to an incoming SMS, as shown in the Log and on Home. */
enum class DeliveryState { FORWARDED, SKIPPED, RETRYING, FAILED }

@Composable
fun StatusChip(state: DeliveryState, modifier: Modifier = Modifier) {
    val extra = ForwarderTheme.extra
    val scheme = MaterialTheme.colorScheme
    val (label, tone) = when (state) {
        DeliveryState.FORWARDED -> R.string.status_forwarded to extra.ok
        DeliveryState.SKIPPED -> R.string.status_skipped to neutralTone()
        DeliveryState.RETRYING -> R.string.status_retrying to extra.warn
        DeliveryState.FAILED -> R.string.status_failed to Tone(scheme.error, scheme.errorContainer)
    }
    Pill(stringResource(label), tone, modifier)
}
