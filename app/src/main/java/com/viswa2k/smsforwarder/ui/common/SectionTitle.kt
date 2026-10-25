package com.viswa2k.smsforwarder.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small-caps section heading with an optional trailing link ("Manage", "See all"). */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
        )
        if (actionLabel != null) {
            Text(
                actionLabel,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAction).padding(vertical = 4.dp),
            )
        }
    }
}
