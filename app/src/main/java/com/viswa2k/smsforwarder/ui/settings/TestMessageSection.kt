package com.viswa2k.smsforwarder.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.CheckResult
import com.viswa2k.smsforwarder.core.Origin
import com.viswa2k.smsforwarder.core.SenderKind
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.ui.common.TypeChip
import com.viswa2k.smsforwarder.ui.rules.telegramHtmlToAnnotated

/** Sample from the mock; the owner can paste any SMS instead. */
private const val SAMPLE_SENDER = "VM-HDFCBK"
private const val SAMPLE_BODY =
    "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank"

@Composable
fun TestMessageSection(vm: SettingsViewModel) {
    val result by vm.check.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var sender by rememberSaveable { mutableStateOf(SAMPLE_SENDER) }
    var body by rememberSaveable { mutableStateOf(SAMPLE_BODY) }
    var whatsApp by rememberSaveable { mutableStateOf(false) }

    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(stringResource(R.string.test_source))
            FilterChip(selected = !whatsApp, onClick = { whatsApp = false }, label = { Text(stringResource(R.string.sources_sms)) })
            FilterChip(selected = whatsApp, onClick = { whatsApp = true }, label = { Text(stringResource(R.string.sources_whatsapp)) })
        }
        OutlinedTextField(
            sender, { sender = it },
            label = { Text(stringResource(if (whatsApp) R.string.test_chat_name else R.string.test_sender)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            body, { body = it },
            label = { Text(stringResource(R.string.test_message)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(stringResource(R.string.test_nothing_sent), Modifier.weight(1f))
            Button(onClick = { vm.runCheck(sender, body, if (whatsApp) Origin.WHATSAPP else Origin.SMS) }, enabled = !checking && sender.isNotBlank() && body.isNotBlank()) {
                Text(stringResource(R.string.test_check))
            }
        }
        result?.let { CheckResultView(it) }
    }
}

@Composable
private fun CheckResultView(r: CheckResult) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.paused) Muted(stringResource(R.string.test_paused_note))
        Kv(stringResource(R.string.test_detected)) { TypeChip(r.type) }
        if (r.extracted.isNotBlank()) Kv(stringResource(R.string.test_extracted)) { Text(r.extracted, fontSize = 13.5.sp) }
        Kv(stringResource(R.string.test_sender_kind)) { Text(senderKindLabel(r.senderKind), fontSize = 13.5.sp) }
        Kv(stringResource(R.string.test_matched)) {
            if (r.matchedRules.isEmpty()) Muted(skipLabel(r.skip))
            else Text(r.matchedRules.joinToString(", "), fontSize = 13.5.sp)
        }
        Kv(stringResource(R.string.test_would_send)) {
            if (r.destinations.isEmpty()) Muted(stringResource(R.string.test_nothing))
            else Text(r.destinations.joinToString(", "), fontSize = 13.5.sp)
        }
        r.telegramPreview?.let {
            Preview(stringResource(R.string.test_preview_telegram), telegramHtmlToAnnotated(it, MaterialTheme.colorScheme.surface))
        }
        r.smsPreview?.let { Preview(stringResource(R.string.test_preview_sms), AnnotatedString(it)) }
    }
}

@Composable
private fun Kv(key: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.width(110.dp))
        Column(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun Preview(title: String, text: AnnotatedString) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        )
    }
}

@Composable
private fun senderKindLabel(k: SenderKind): String = stringResource(
    when (k) {
        SenderKind.BUSINESS -> R.string.test_kind_business
        SenderKind.CONTACT -> R.string.test_kind_contact
        SenderKind.UNKNOWN_NUMBER -> R.string.test_kind_unknown
        SenderKind.UNVERIFIED -> R.string.test_kind_unverified
    },
)

@Composable
private fun skipLabel(s: SkipReason?): String = stringResource(
    when (s) {
        SkipReason.SPAM -> R.string.test_skip_spam
        SkipReason.CONTACTS_PERMISSION_MISSING -> R.string.test_skip_contacts
        else -> R.string.test_skip_no_rule
    },
)
