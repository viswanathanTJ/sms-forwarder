package com.viswa2k.smsforwarder.core

/** Forwarder message type (spec 5.1). [code] is stored; never renumber. [SPAM] is not user-selectable. */
enum class MessageType(val code: Int, val label: String, val emoji: String) {
    MISSED_CALL(1, "Missed call", "📞"),
    OTP(2, "OTP", "🔐"),
    TRANSACTION(3, "Transaction", "💸"),
    BALANCE(4, "Balance alert", "🏦"),
    BILL(5, "Bills & dues", "🧾"),
    DELIVERY(6, "Delivery", "📦"),
    IMPORTANT(7, "Other important", "⭐"),
    PERSONAL(8, "Personal", "👤"),
    SPAM(9, "Spam", "🚫");

    companion object {
        fun fromCode(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}
