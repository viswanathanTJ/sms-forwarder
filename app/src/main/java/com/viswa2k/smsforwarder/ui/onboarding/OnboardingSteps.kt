package com.viswa2k.smsforwarder.ui.onboarding

/** The onboarding pages, in order. */
enum class OnbStep { WELCOME, PERMISSIONS, BATTERY, AUTOSTART, TELEGRAM, READY }

/** Status chip of one permission row. */
enum class PermStatus { GRANTED, NEEDED, OPTIONAL }

/** What the permissions page's main action does. */
enum class PermAction { NONE, REQUEST, OPEN_SETTINGS }

/** What is set up right now (from the health checks + settings). */
data class SetupSnapshot(
    val smsOk: Boolean = false,
    val contactsOk: Boolean = false,
    val notificationsOk: Boolean = false,
    val batteryOk: Boolean = false,
    val oemConfirmed: Boolean = false,
    /** A bot token is saved and at least one Telegram chat exists. */
    val telegramReady: Boolean = false,
) {
    companion object {
        /** [health] = HealthItem id -> ok (missing ids count as not ok). */
        fun from(health: Map<String, Boolean>, notificationsOk: Boolean, oemConfirmed: Boolean, telegramReady: Boolean) = SetupSnapshot(
            smsOk = health["sms"] == true,
            contactsOk = health["contacts"] == true,
            notificationsOk = notificationsOk,
            batteryOk = health["battery"] == true,
            oemConfirmed = oemConfirmed,
            telegramReady = telegramReady,
        )
    }
}

/** Advance / completion rules of the paged onboarding (pure; see OnboardingStepsTest). */
object OnboardingSteps {
    val steps: List<OnbStep> = OnbStep.entries

    /** The steps listed with a tick / dot on the Ready page. */
    val checklist: List<OnbStep> = listOf(OnbStep.PERMISSIONS, OnbStep.BATTERY, OnbStep.AUTOSTART, OnbStep.TELEGRAM)

    fun at(index: Int): OnbStep = steps[index.coerceIn(0, steps.lastIndex)]

    fun next(index: Int): Int = (index + 1).coerceAtMost(steps.lastIndex)

    fun previous(index: Int): Int = (index - 1).coerceAtLeast(0)

    /** Only the permissions page blocks (SMS + contacts); notifications are optional; the rest can be skipped. */
    fun canAdvance(step: OnbStep, s: SetupSnapshot): Boolean = when (step) {
        OnbStep.PERMISSIONS -> s.smsOk && s.contactsOk
        OnbStep.READY -> false
        else -> true
    }

    fun isComplete(step: OnbStep, s: SetupSnapshot): Boolean = when (step) {
        OnbStep.WELCOME -> true
        OnbStep.PERMISSIONS -> s.smsOk && s.contactsOk
        OnbStep.BATTERY -> s.batteryOk
        OnbStep.AUTOSTART -> s.oemConfirmed
        OnbStep.TELEGRAM -> s.telegramReady
        OnbStep.READY -> s.smsOk && s.contactsOk
    }

    fun permStatus(granted: Boolean, required: Boolean): PermStatus = when {
        granted -> PermStatus.GRANTED
        required -> PermStatus.NEEDED
        else -> PermStatus.OPTIONAL
    }

    /**
     * No action while only the optional notification permission is missing (after one denial Android stops
     * showing its dialog). After a refusal of a required one, App info is opened instead of the dialog.
     */
    fun permAction(missingRequired: Boolean, askedBefore: Boolean): PermAction = when {
        !missingRequired -> PermAction.NONE
        askedBefore -> PermAction.OPEN_SETTINGS
        else -> PermAction.REQUEST
    }
}
