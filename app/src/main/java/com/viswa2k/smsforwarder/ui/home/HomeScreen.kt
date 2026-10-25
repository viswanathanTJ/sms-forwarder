package com.viswa2k.smsforwarder.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.core.SkipReason
import com.viswa2k.smsforwarder.ui.common.SectionTitle
import com.viswa2k.smsforwarder.ui.common.StatusChip
import com.viswa2k.smsforwarder.ui.common.TypeBadge
import com.viswa2k.smsforwarder.ui.common.TypeChip
import com.viswa2k.smsforwarder.ui.theme.BrandPrimary
import com.viswa2k.smsforwarder.ui.theme.HeroGradientEnd
import java.util.Date

@Composable
fun HomeScreen(
    requireUnlock: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenRule: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(AppGraph.from(context)))
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val issues = health.count { !it.ok }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.home_open_settings))
            }
        }

        HeroCard(
            state = state,
            issues = issues,
            onToggle = { on -> requireUnlock { vm.setMaster(on) } },
            onAttention = onOpenSettings,
        )

        if (state.loaded && !state.hasDestinations) {
            Spacer(Modifier.height(12.dp))
            SetupCard(onOpenSettings)
        }

        SectionTitle(stringResource(R.string.home_section_rules), actionLabel = stringResource(R.string.home_manage), onAction = onOpenRules)
        AppCard {
            if (state.rules.isEmpty()) {
                MutedText(stringResource(R.string.home_no_rules))
            }
            state.rules.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                RuleItem(row, onClick = { onOpenRule(row.rule.id) }, onToggle = { on -> requireUnlock { vm.setRuleEnabled(row.rule.id, on) } })
            }
        }

        SectionTitle(stringResource(R.string.home_section_recent), actionLabel = stringResource(R.string.home_see_all), onAction = onOpenLog)
        AppCard {
            if (state.recent.isEmpty()) {
                MutedText(stringResource(R.string.home_no_recent))
            }
            state.recent.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                RecentItem(row)
            }
        }
    }
}

@Composable
private fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun MutedText(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

@Composable
private fun HeroCard(state: HomeState, issues: Int, onToggle: (Boolean) -> Unit, onAttention: () -> Unit) {
    val switchLabel = stringResource(R.string.home_master_switch)
    val onHero = Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(BrandPrimary, HeroGradientEnd)))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.masterOn) R.string.home_hero_on else R.string.home_hero_paused),
                    color = onHero, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
                val device = state.deviceName.ifBlank { stringResource(R.string.home_device_default) }
                Text(
                    when {
                        !state.masterOn -> stringResource(R.string.home_hero_sub_paused)
                        issues > 0 -> stringResource(R.string.home_hero_sub_on_attention, device)
                        else -> stringResource(R.string.home_hero_sub_on, device)
                    },
                    color = onHero.copy(alpha = 0.85f), fontSize = 12.sp,
                )
            }
            Switch(
                modifier = Modifier.semantics { contentDescription = switchLabel },
                checked = state.masterOn,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BrandPrimary,
                    checkedTrackColor = Color.White,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(state.counts.forwarded, R.string.home_stat_forwarded, Modifier.weight(1f))
            Stat(state.counts.skipped, R.string.home_stat_skipped, Modifier.weight(1f))
            Stat(state.counts.retrying, R.string.home_stat_retrying, Modifier.weight(1f))
        }
        if (issues > 0) {
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAttention)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = onHero, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.home_needs_attention, issues),
                    color = onHero, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(label), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
}

@Composable
private fun SetupCard(onSetup: () -> Unit) {
    AppCard {
        Text(stringResource(R.string.home_setup_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            stringResource(R.string.home_setup_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Button(onClick = onSetup) { Text(stringResource(R.string.home_setup_action)) }
    }
}

@Composable
private fun RuleItem(row: RuleRow, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val rule = row.rule
    val types = if (rule.allMessages || rule.types.isEmpty()) stringResource(R.string.rule_all_messages)
    else rule.types.sortedBy { it.code }.joinToString(", ") { it.label }
    val sender = stringResource(
        when (rule.sender) {
            SenderCondition.ANYONE -> R.string.sender_anyone
            SenderCondition.NOT_IN_CONTACTS -> R.string.sender_not_in_contacts
            SenderCondition.CONTACTS_ONLY -> R.string.sender_contacts_only
        },
    )
    val dests = row.destinationLabels.joinToString(", ").ifEmpty { stringResource(R.string.rule_no_destination) }
    val firstType = if (rule.allMessages) null else rule.types.minByOrNull { it.code }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TypeBadge(firstType)
        Column(Modifier.weight(1f)) {
            Text(rule.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.rule_summary, types, sender, dests),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val switchLabel = stringResource(R.string.rule_switch, rule.name)
        Switch(
            modifier = Modifier.semantics { contentDescription = switchLabel },
            checked = rule.enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun RecentItem(row: RecentRow) {
    val context = LocalContext.current
    val time = remember(row.receivedAt) { DateFormat.getTimeFormat(context).format(Date(row.receivedAt)) }
    val note = when {
        row.skip != null -> stringResource(
            when (row.skip) {
                SkipReason.PAUSED -> R.string.skip_paused
                SkipReason.SPAM -> R.string.skip_spam
                SkipReason.NO_RULE -> R.string.skip_no_rule
                SkipReason.CONTACTS_PERMISSION_MISSING -> R.string.skip_contacts_permission
            },
        )
        row.sentTo.isNotEmpty() -> stringResource(R.string.recent_sent_to, row.sentTo.joinToString(", "))
        else -> stringResource(R.string.recent_matched_nothing_sent)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.sender, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(
            row.body.replace('\n', ' '),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.type?.let { TypeChip(it) }
            StatusChip(row.state)
        }
        Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
