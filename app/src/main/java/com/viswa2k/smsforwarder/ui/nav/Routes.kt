package com.viswa2k.smsforwarder.ui.nav

object Routes {
    const val HOME = "home"
    const val RULES = "rules"
    const val RULE_ID_ARG = "id"
    const val RULE_EDIT = "rules/{$RULE_ID_ARG}"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val SECTION_ARG = "section"
    /** A Settings sub-page, child of the PIN-gated Settings root. */
    const val SETTINGS_SECTION = "settings/{$SECTION_ARG}"
    const val ONBOARDING = "onboarding"

    /** Rule editor route; id 0 = new rule. */
    fun ruleEdit(id: Long) = "rules/$id"

    fun settingsSection(key: String) = "settings/$key"

    val TABS = listOf(HOME, RULES, LOG, SETTINGS)
}
