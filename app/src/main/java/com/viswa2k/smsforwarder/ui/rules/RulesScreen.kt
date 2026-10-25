package com.viswa2k.smsforwarder.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.ui.common.Pill
import com.viswa2k.smsforwarder.ui.common.TypeBadge
import com.viswa2k.smsforwarder.ui.home.RuleRow

@Composable
fun RulesScreen(
    requireUnlock: (() -> Unit) -> Unit,
    onOpenRule: (Long) -> Unit,
    onNewRule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: RulesViewModel = viewModel(factory = RulesViewModel.factory(AppGraph.from(LocalContext.current)))
    val rules by vm.rules.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 88.dp),
        ) {
            Text(
                stringResource(R.string.title_rules),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 12.dp),
            )
            Text(
                stringResource(R.string.rules_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
            )
            val list = rules
            if (list != null) {
                RuleCard {
                    if (list.isEmpty()) {
                        Text(
                            stringResource(R.string.rules_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    list.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
                        RuleListItem(
                            row = row,
                            canMoveUp = i > 0,
                            canMoveDown = i < list.lastIndex,
                            onClick = { onOpenRule(row.rule.id) },
                            onToggle = { on -> requireUnlock { vm.setEnabled(row.rule.id, on) } },
                            onMove = { delta -> requireUnlock { vm.move(row.rule.id, delta) } },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rules_never_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.rules_never_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                        )
                    }
                    Pill(stringResource(R.string.rules_always), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surface)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewRule,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.rules_new), fontWeight = FontWeight.SemiBold) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
internal fun RuleCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
private fun RuleListItem(
    row: RuleRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
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
    val hasDestination = row.destinationLabels.isNotEmpty()
    val dests = row.destinationLabels.joinToString(", ").ifEmpty { stringResource(R.string.rule_no_destination) }
    val firstType = if (rule.allMessages) null else rule.types.minByOrNull { it.code }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypeBadge(firstType)
        Column(Modifier.weight(1f)) {
            Text(rule.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.rule_summary, types, sender, dests),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Column {
            val up = stringResource(R.string.rules_move_up, rule.name)
            val down = stringResource(R.string.rules_move_down, rule.name)
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = up)
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = down)
            }
        }
        val switchLabel = stringResource(R.string.rule_switch, rule.name)
        Switch(
            modifier = Modifier.semantics { contentDescription = switchLabel },
            checked = rule.enabled,
            // A rule without destinations cannot be enabled (spec 5.3); it can still be switched off.
            enabled = rule.enabled || hasDestination,
            onCheckedChange = onToggle,
        )
    }
}
