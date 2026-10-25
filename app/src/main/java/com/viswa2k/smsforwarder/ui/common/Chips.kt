package com.viswa2k.smsforwarder.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viswa2k.smsforwarder.ui.theme.Tone

/** Pill label used by [TypeChip] and [StatusChip]. */
@Composable
internal fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = fg,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier) = Pill(text, tone.fg, tone.soft, modifier)

@Composable
internal fun neutralTone() = Tone(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
