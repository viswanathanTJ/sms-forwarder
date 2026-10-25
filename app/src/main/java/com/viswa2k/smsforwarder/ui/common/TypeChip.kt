package com.viswa2k.smsforwarder.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viswa2k.smsforwarder.core.MessageType

/** Message-type pill (e.g. "Transaction") in the type's colour; neutral for types without one. */
@Composable
fun TypeChip(type: MessageType, modifier: Modifier = Modifier) {
    val tone = when (type) {
        MessageType.OTP, MessageType.TRANSACTION, MessageType.BALANCE, MessageType.MISSED_CALL -> type.tone()
        else -> neutralTone()
    }
    Pill(type.label, tone, modifier)
}
