package com.viswa2k.smsforwarder.core

/** A thing that stops forwarding from working, with the stable key used for alert de-duplication. */
enum class Problem(val key: String, val text: String) {
    SMS_PERMISSION("sms", "SMS permission was removed"),
    CONTACTS_PERMISSION("contacts", "Contacts permission was removed"),
    NOTIFICATIONS("notif", "Notifications are off"),
    BATTERY_OPTIMIZED("battery", "Battery optimisation is back on"),
    CLASSIFIER("classifier", "Message filter failed to load"),
    PAUSED("paused", "Forwarding is switched off"),
    NOTIF_ACCESS("notif_access", "WhatsApp notification access was removed"),
}

/** Decides which problem alerts to send: once when new, again after 24 h, and a "resolved" when gone. */
object ProblemAlerts {
    const val REPEAT_MS = 24L * 3_600_000

    /** [raise]/[resolve] are problem keys; [newState] maps key -> last time an alert was sent. */
    data class Plan(val raise: List<String>, val resolve: List<String>, val newState: Map<String, Long>)

    fun plan(state: Map<String, Long>, current: Set<String>, now: Long): Plan {
        val raise = current.filter { key -> state[key]?.let { now - it >= REPEAT_MS } ?: true }.sorted()
        val resolve = state.keys.filter { it !in current }.sorted()
        val newState = current.associateWith { key -> if (key in raise) now else state.getValue(key) }
        return Plan(raise, resolve, newState)
    }

    /** State after trying to send a "raise" alert: recorded only when sent; a failure keeps any older timestamp. */
    fun afterRaise(state: Map<String, Long>, key: String, now: Long, sent: Boolean): Map<String, Long> =
        if (sent) state + (key to now) else state

    /** State after trying to send a "resolved" alert: removed only when sent, so a failure retries next run. */
    fun afterResolve(state: Map<String, Long>, key: String, sent: Boolean): Map<String, Long> =
        if (sent) state - key else state
}
