package com.viswa2k.smsforwarder.core

/** Where a message came from. Stored in `LogEntity.origin` as [code]. */
enum class Origin(val code: Int) {
    SMS(0), WHATSAPP(1);

    companion object {
        /** Unknown codes read back as [SMS] (the column default). */
        fun fromCode(code: Int): Origin = entries.firstOrNull { it.code == code } ?: SMS
    }
}
