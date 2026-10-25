package com.viswa2k.smsforwarder.core

/** v1 settings as stored in the old DataStore (spec 5.10). Blank strings mean "not set". */
data class V1Settings(
    val token: String,
    val chatIds: List<String>,
    val smsNumber: String,
    val deviceAlias: String,
    val serviceOn: Boolean,
    val telegramOn: Boolean,
    val smsOn: Boolean,
    val skipContacts: Boolean,
)

/**
 * What to write into v2 storage. [destinations] have id 0 (database assigns); [assignToRules] are
 * indexes into [destinations] that the default rules point at.
 */
data class MigrationPlan(
    val token: String?,
    val destinations: List<Destination>,
    val assignToRules: List<Int>,
    val sender: SenderCondition,
    val deviceName: String,
    val masterOn: Boolean,
)

object V1Migration {
    /** Splits v1's comma separated `TELEGRAM_USER_IDS`, dropping blanks. */
    fun splitChats(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** `null` = no v1 data (fresh install): default rules only, no destinations, master off. */
    fun plan(v1: V1Settings?): MigrationPlan {
        if (v1 == null) return MigrationPlan(null, emptyList(), emptyList(), SenderCondition.ANYONE, "", false)
        val destinations = mutableListOf<Destination>()
        val assign = mutableListOf<Int>()
        v1.chatIds.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, chat ->
            if (v1.telegramOn) assign += destinations.size
            destinations += Destination(0, Channel.TELEGRAM, "Chat ${i + 1}", chat)
        }
        val number = v1.smsNumber.trim()
        if (number.isNotEmpty()) {
            if (v1.smsOn) assign += destinations.size
            destinations += Destination(0, Channel.SMS, "SMS", number)
        }
        return MigrationPlan(
            token = v1.token.trim().ifEmpty { null },
            destinations = destinations,
            assignToRules = assign,
            sender = if (v1.skipContacts) SenderCondition.NOT_IN_CONTACTS else SenderCondition.ANYONE,
            deviceName = v1.deviceAlias.trim(),
            masterOn = v1.serviceOn,
        )
    }
}
