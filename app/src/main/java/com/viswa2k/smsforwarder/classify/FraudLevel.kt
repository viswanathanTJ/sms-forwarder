// Ported from SMS app: app/src/main/java/com/viswa2k/sms/data/db/FraudLevel.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/**
 * Fraud class of a message (the original's `f_sms`, spec 04b §3.2). [code] is the stored value and
 * keeps the original numbers. Chip / banner labels are in the comments.
 */
enum class FraudLevel(val code: Int) {
    /** Not flagged. */
    NONE(FraudCode.NONE),
    /** "Scam" / "Scam message"; chip SCAM. */
    SCAM(FraudCode.SCAM),
    /** "Financial Risk" / "Financial risk"; no chip. */
    FINANCIAL_RISK(FraudCode.FINANCIAL_RISK),
    /** "Suspicious" / "Suspicious message"; chip SUSPICIOUS. */
    SUSPICIOUS(FraudCode.SUSPICIOUS),
    /** "Misleading" / "Misleading message"; chip MISLEADING. */
    MISLEADING(FraudCode.MISLEADING),
    /** "Fake" / "Fake claims"; chip FAKE. */
    FAKE(FraudCode.FAKE);

    companion object {
        fun fromCode(code: Int): FraudLevel = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * Stored [FraudLevel.code] values as compile-time constants, so Room `@Query` strings can reference
 * them (`"... = ${FraudCode.X}"`) and stay correct when codes change.
 */
object FraudCode {
    const val NONE = 0
    const val SCAM = 1
    const val FINANCIAL_RISK = 2
    const val SUSPICIOUS = 3
    const val MISLEADING = 4
    const val FAKE = 5
}
