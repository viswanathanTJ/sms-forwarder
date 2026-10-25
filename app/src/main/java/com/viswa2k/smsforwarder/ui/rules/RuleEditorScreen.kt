package com.viswa2k.smsforwarder.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.core.Style
import com.viswa2k.smsforwarder.ui.common.SectionTitle
import com.viswa2k.smsforwarder.ui.theme.ForwarderTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    ruleId: Long,
    requireUnlock: (() -> Unit) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: RuleEditorViewModel = viewModel(
        key = "rule-editor-$ruleId",
        factory = RuleEditorViewModel.factory(AppGraph.from(LocalContext.current), ruleId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(missing) { if (missing) onClose() }
    val s = state
    val dests = destinations
    if (s == null || dests == null) return

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
            }
            Text(
                stringResource(if (ruleId == 0L) R.string.editor_title_new else R.string.editor_title_edit),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (ruleId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.editor_delete))
                }
            }
        }

        OutlinedTextField(
            value = s.name,
            onValueChange = { v -> vm.update { it.copy(name = v) } },
            label = { Text(stringResource(R.string.editor_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(R.string.editor_types))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.allMessages,
                onClick = { vm.update { it.toggleAll() } },
                label = { Text("✉ ${stringResource(R.string.rule_all_messages)}") },
            )
            MessageType.entries.filter { it != MessageType.SPAM }.forEach { t ->
                FilterChip(
                    selected = t in s.types,
                    onClick = { vm.update { it.toggleType(t) } },
                    label = { Text("${t.emoji} ${t.label}") },
                )
            }
        }

        SectionTitle(stringResource(R.string.editor_sender))
        Segmented(
            options = listOf(
                SenderCondition.ANYONE to stringResource(R.string.sender_anyone),
                SenderCondition.NOT_IN_CONTACTS to stringResource(R.string.sender_not_in_contacts),
                SenderCondition.CONTACTS_ONLY to stringResource(R.string.sender_contacts_only),
            ),
            selected = s.sender,
            onSelect = { v -> vm.update { it.copy(sender = v) } },
        )

        SectionTitle(stringResource(R.string.editor_send_to))
        RuleCard {
            if (dests.isEmpty()) {
                Text(stringResource(R.string.editor_no_destinations), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.editor_open_settings))
                }
            }
            dests.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val checked = d.id in s.destinationIds
                Row(
                    Modifier.fillMaxWidth().clickable { vm.update { it.toggleDestination(d.id) } }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val telegram = d.channel == Channel.TELEGRAM
                    Text(
                        if (telegram) "✈" else "💬",
                        fontSize = 17.sp,
                        color = if (telegram) ForwarderTheme.extra.telegram else MaterialTheme.colorScheme.onSurface,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(d.label)
                        Text(
                            if (telegram) stringResource(R.string.editor_telegram_sub) else stringResource(R.string.editor_sms_sub, d.address),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = { vm.update { it.toggleDestination(d.id) } })
                }
            }
        }

        SectionTitle(stringResource(R.string.editor_style))
        Segmented(
            options = listOf(
                Style.SMART to stringResource(R.string.editor_style_smart),
                Style.ORIGINAL to stringResource(R.string.editor_style_original),
                Style.CUSTOM to stringResource(R.string.editor_style_custom),
            ),
            selected = s.style,
            onSelect = { v -> vm.update { it.copy(style = v) } },
        )
        if (s.style == Style.CUSTOM) {
            OutlinedTextField(
                value = s.customTemplate,
                onValueChange = { v -> vm.update { it.copy(customTemplate = v) } },
                label = { Text(stringResource(R.string.editor_template)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.editor_token_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }

        SectionTitle(stringResource(R.string.editor_preview))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview.forEach { html ->
                Surface(
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        telegramHtmlToAnnotated(html, MaterialTheme.colorScheme.surfaceVariant),
                        fontSize = 13.5.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Button(
            onClick = { requireUnlock { vm.save(); onClose() } },
            enabled = s.canSave,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) { Text(stringResource(R.string.editor_save)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_body, s.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    requireUnlock { vm.delete(); onClose() }
                }) { Text(stringResource(R.string.editor_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.editor_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text(label, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

/**
 * Renders the Telegram HTML subset the formatter emits (`<b>`, `<i>`, `<code>` and the `&amp; &lt; &gt;`
 * entities) as styled text. Unknown tags are dropped, their text kept.
 */
internal fun telegramHtmlToAnnotated(html: String, codeBackground: Color = Color.Unspecified): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        var bold = 0
        var italic = 0
        var code = 0
        val open = ArrayDeque<Int>()
        fun styleNow() = SpanStyle(
            fontWeight = if (bold > 0) FontWeight.Bold else null,
            fontStyle = if (italic > 0) FontStyle.Italic else null,
            fontFamily = if (code > 0) FontFamily.Monospace else null,
            background = if (code > 0) codeBackground else Color.Unspecified,
        )
        fun emit(text: String) = withStyle(styleNow()) { append(text) }
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i)
                if (end > i) {
                    when (html.substring(i + 1, end).trim().lowercase()) {
                        "b" -> bold++
                        "/b" -> if (bold > 0) bold--
                        "i" -> italic++
                        "/i" -> if (italic > 0) italic--
                        "code" -> code++
                        "/code" -> if (code > 0) code--
                    }
                    i = end + 1
                    continue
                }
            }
            if (c == '&') {
                val entity = listOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">").firstOrNull { html.startsWith(it.first, i) }
                if (entity != null) {
                    emit(entity.second)
                    i += entity.first.length
                    continue
                }
            }
            val next = html.indexOfAny(charArrayOf('<', '&'), i + 1).let { if (it < 0) html.length else it }
            emit(html.substring(i, next))
            i = next
        }
    }
