// Ported from SMS app: app/src/main/java/com/viswa2k/sms/data/db/MessageCategory.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/**
 * Classifier category of a message: the original's `s_sms_to` output (spec 04b §3.1). The numbers are
 * kept from the original so imported data and the spec tables line up. [code] is the stored value and
 * must never change for an existing entry.
 *
 * Inbox tab mapping (spec 02 §4.2): [PROMOTIONAL] and [BLOCKED_DELETE] live in the Promotional store;
 * everything else is in the main store (Important). The OTP tab counts [isOtp] codes. Personal /
 * Unknown are contact-based filters, not categories.
 */
enum class MessageCategory(val code: Int) {
    /** Not classified yet (outgoing messages stay here). */
    UNCLASSIFIED(CategoryCode.UNCLASSIFIED),
    /** Service / government-style sender (`XX-<digits>` or 6-digit header) with nothing else matched: important. */
    SERVICE(CategoryCode.SERVICE),
    /** Promotional / spam / fraud: Promotional store (block output 1). */
    PROMOTIONAL(CategoryCode.PROMOTIONAL),
    /** Known (saved / sent-to) phone number, no rule hit (block-unknown mode only, spec 04a). */
    KNOWN_NUMBER(CategoryCode.KNOWN_NUMBER),
    /** Normal / important message. */
    IMPORTANT(CategoryCode.IMPORTANT),
    /** One-time password. */
    OTP(CategoryCode.OTP),
    /** Blocked by a user rule with "instant delete" (spec 04a §3.2). */
    BLOCKED_DELETE(CategoryCode.BLOCKED_DELETE),
    /** Order / delivery status update. */
    DELIVERY(CategoryCode.DELIVERY),
    /** Bank account / balance alert without a transaction amount. */
    BALANCE_ALERT(CategoryCode.BALANCE_ALERT),
    /** Other important sub-type (meaning unresolved in the original; kept for import). */
    IMPORTANT_OTHER(CategoryCode.IMPORTANT_OTHER),
    /** Smart alert: bill due, statement, travel, booking (Reminders). */
    SMART_ALERT(CategoryCode.SMART_ALERT),
    /** Bank / wallet transaction (debit or credit). */
    TRANSACTION(CategoryCode.TRANSACTION),
    /** OTP inside a payment / transaction-like message. */
    OTP_TRANSACTION(CategoryCode.OTP_TRANSACTION),
    /** OTP variant (kept for import). */
    OTP_VARIANT(CategoryCode.OTP_VARIANT);

    /** Counted in the OTP tab (spec 04b §3.1: codes 4/34/38). */
    val isOtp: Boolean get() = this == OTP || this == OTP_TRANSACTION || this == OTP_VARIANT

    /** Belongs to the Promotional (block) store. */
    val inSpamStore: Boolean get() = this == PROMOTIONAL || this == BLOCKED_DELETE

    companion object {
        fun fromCode(code: Int): MessageCategory = entries.firstOrNull { it.code == code } ?: UNCLASSIFIED
    }
}

/**
 * Stored [MessageCategory.code] values as compile-time constants, so Room `@Query` strings can reference
 * them (`"... = ${CategoryCode.X}"`) and stay correct when codes change.
 */
object CategoryCode {
    const val UNCLASSIFIED = -1
    const val SERVICE = 0
    const val PROMOTIONAL = 1
    const val KNOWN_NUMBER = 2
    const val IMPORTANT = 3
    const val OTP = 4
    const val BLOCKED_DELETE = 5
    const val DELIVERY = 11
    const val BALANCE_ALERT = 30
    const val IMPORTANT_OTHER = 31
    const val SMART_ALERT = 32
    const val TRANSACTION = 33
    const val OTP_TRANSACTION = 34
    const val OTP_VARIANT = 38

    /** SQL list of the OTP-tab codes, for `IN (...)`. */
    const val OTP_CODES_SQL = "$OTP, $OTP_TRANSACTION, $OTP_VARIANT"

    /** SQL list of the Promotional-store codes, for `IN (...)`. */
    const val SPAM_STORE_CODES_SQL = "$PROMOTIONAL, $BLOCKED_DELETE"
}
