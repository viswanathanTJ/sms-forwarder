package com.viswa2k.smsforwarder.core

/** Who the sender must be for a rule to match (spec 5.3). [code] is stored; never renumber. */
enum class SenderCondition(val code: Int) { ANYONE(0), NOT_IN_CONTACTS(1), CONTACTS_ONLY(2) }

/** How the forwarded text is rendered. [code] is stored; never renumber. */
enum class Style(val code: Int) { SMART(0), ORIGINAL(1), CUSTOM(2) }

/** Delivery channel of a [Destination]. [code] is stored; never renumber. */
enum class Channel(val code: Int) { TELEGRAM(0), SMS(1) }

/** A place an SMS can be forwarded to. [address] is a Telegram chat id or a phone number. */
data class Destination(
    val id: Long,
    val channel: Channel,
    val label: String,
    val address: String,
    val topicId: Long? = null,
    val simSubscriptionId: Int? = null,
)

/** A forwarding rule. When [allMessages] is true, [types] is ignored. */
data class Rule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val allMessages: Boolean,
    val types: Set<MessageType>,
    val sender: SenderCondition,
    val destinationIds: List<Long>,
    val style: Style,
    val customTemplate: String?,
    val position: Int,
)
