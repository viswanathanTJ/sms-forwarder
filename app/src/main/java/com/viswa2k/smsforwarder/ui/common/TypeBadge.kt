package com.viswa2k.smsforwarder.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme
import com.viswa2k.smsforwarder.ui.theme.Tone

/** Colour tone of a message type (mock: OTP violet, Transaction teal, Missed call orange, rest neutral). */
@Composable
fun MessageType?.tone(): Tone {
    val extra = ForwarderTheme.extra
    return when (this) {
        MessageType.OTP -> extra.otp
        MessageType.TRANSACTION, MessageType.BALANCE -> extra.transaction
        MessageType.MISSED_CALL -> extra.missedCall
        else -> extra.neutral
    }
}

/** 38dp rounded square with the type's emoji; [type] null shows [fallbackEmoji] on neutral (All messages). */
@Composable
fun TypeBadge(type: MessageType?, modifier: Modifier = Modifier, fallbackEmoji: String = "✉") {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(type.tone().soft),
        contentAlignment = Alignment.Center,
    ) {
        Text(type?.emoji ?: fallbackEmoji, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
