package com.viswa2k.smsforwarder.service

import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.Problem
import com.viswa2k.smsforwarder.core.ProblemAlerts
import com.viswa2k.smsforwarder.data.prefs.SettingsStore
import com.viswa2k.smsforwarder.core.SendResult
import kotlinx.coroutines.flow.first

/**
 * Sends problem alerts straight through Telegram (not via rules/outbox, so a broken queue cannot
 * suppress them). Target: the chosen alerts destination, else the first Telegram destination
 * (ruling 14); with none, nothing is sent and the state is untouched so alerts fire once one exists.
 */
class ProblemAlerter(private val graph: AppGraph) {
    private val health = HealthChecker(graph.appContext)

    suspend fun check(now: Long = System.currentTimeMillis()) {
        val target = target() ?: return
        val failing = failingDestinations(now)
        val texts = LinkedHashMap<String, String>()
        for (p in health.problems()) texts[p.key] = p.text
        failing.forEach { (d, error) ->
            texts["dest:${d.id}"] = "Sending to ${d.label} has failed for over an hour: ${error ?: "unknown error"}"
        }
        val state = SettingsStore.parseAlertState(graph.settings.alertState.first())
        val plan = ProblemAlerts.plan(state, texts.keys, now)
        val device = device()
        val newState = plan.newState.toMutableMap()
        for (key in plan.raise) {
            if (!send(target, "⚠", device, texts.getValue(key))) newState.remove(key)
        }
        for (key in plan.resolve) {
            // Text of a resolved problem is no longer computed; use the static text or a generic one.
            val text = Problem.entries.firstOrNull { it.key == key }?.text
                ?: failingLabel(key)
            if (!send(target, "✅", device, "resolved — $text")) newState[key] = state.getValue(key)
        }
        graph.settings.setAlertState(SettingsStore.encodeAlertState(newState))
    }

    /** One-off informational message (e.g. REBOOTED). Skipped silently without a Telegram destination. */
    suspend fun info(text: String) {
        val target = target() ?: return
        send(target, "ℹ", device(), text)
    }

    private suspend fun failingDestinations(now: Long): List<Pair<Destination, String?>> =
        graph.destinations.all().mapNotNull { d ->
            val since = graph.outbox.failingSince(d.id) ?: return@mapNotNull null
            if (now - since <= HOUR_MS) null else d to graph.outbox.lastErrorFor(d.id)
        }

    private suspend fun failingLabel(key: String): String {
        val id = key.removePrefix("dest:").toLongOrNull()
        val label = graph.destinations.all().firstOrNull { it.id == id }?.label
        return if (label != null) "Sending to $label works again" else "A destination works again"
    }

    private suspend fun target(): Destination? {
        val all = graph.destinations.all().filter { it.channel == Channel.TELEGRAM }
        val chosen = graph.settings.alertsDestinationId.first()
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
    }

    private suspend fun device(): String =
        graph.settings.deviceName.first().ifBlank { android.os.Build.MODEL }

    private suspend fun send(target: Destination, icon: String, device: String, text: String): Boolean {
        val html = "$icon ${MessageFormatter.escapeHtml(device)}: ${MessageFormatter.escapeHtml(text)}"
        return graph.telegram.send(target.address, target.topicId, html.take(MessageFormatter.TELEGRAM_MAX)) == SendResult.Sent
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
