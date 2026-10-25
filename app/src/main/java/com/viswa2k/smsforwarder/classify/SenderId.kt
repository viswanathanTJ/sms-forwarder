// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/SenderId.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/** Kind of sender address (spec 04b §4.1 step 2, §5.6; spec 05 §2). */
enum class SenderType {
    /** Ordinary phone number (personal path). */
    PHONE_NUMBER,
    /** Digits-only code shorter than 9 digits (e.g. `57575`, a 6-digit service header), or `+91` + 6–8 digits. */
    SHORT_CODE,
    /** Alphanumeric header (`AX-HDFCBK`, `AMAZON`, `JD-SWIGGY-P`). */
    ALPHANUMERIC,
    /** E-mail style sender (`name@domain`). */
    EMAIL,
    /** Empty / hidden sender. */
    UNKNOWN,
}

/**
 * Indian DLT header suffix (TRAI): `-S` service, `-T` transactional, `-P` promotional, `-G` government.
 */
enum class DltSuffix {
    SERVICE, TRANSACTIONAL, PROMOTIONAL, GOVERNMENT, NONE;

    companion object {
        fun of(letter: Char?): DltSuffix = when (letter?.uppercaseChar()) {
            'S' -> SERVICE
            'T' -> TRANSACTIONAL
            'P' -> PROMOTIONAL
            'G' -> GOVERNMENT
            else -> NONE
        }
    }
}

/** Three-way split used by the inbox tabs (spec 02 §4.2): contact number, other number, business. */
enum class SenderClass { PERSONAL, UNKNOWN, BUSINESS }

/** A parsed sender address. */
data class SenderInfo(
    /** Address as received (trimmed). */
    val raw: String,
    val type: SenderType,
    /**
     * Canonical id: the 6-character header id for business headers (`HDFCBK`, `650025`), the digits of
     * a short code, or [raw] for phone numbers / e-mail.
     */
    val canonicalId: String,
    /** Two-letter operator / circle prefix (`AX`, `VM`, `JD`), upper case, if present. */
    val operatorPrefix: String?,
    /** DLT suffix letter, [DltSuffix.NONE] when absent. */
    val suffix: DltSuffix,
    /**
     * "Business / header sender" routing test (`ML.g`, spec 04b §4.1): not starting with `+` and
     * (contains a letter, or is a digits-only code shorter than 9 digits). Comma-joined multi-sender
     * addresses count when they do not start with `+` and have fewer than 15 non-letter characters.
     */
    val isBusiness: Boolean,
    /** Service / government-style header: `XX-<digits>` or a 6-digit numeric header (spec 04b §4.2 step 9). */
    val isServiceStyle: Boolean,
) {
    /** Digits-only canonical id (numeric header / short code). */
    val isNumericHeader: Boolean get() = canonicalId.isNotEmpty() && canonicalId.all { it.isDigit() }

    /** [SenderClass] given whether the sender is a known contact / sent-to number. */
    fun senderClass(isKnown: Boolean): SenderClass = when {
        isBusiness -> SenderClass.BUSINESS
        isKnown -> SenderClass.PERSONAL
        else -> SenderClass.UNKNOWN
    }
}

/** Parses sender addresses (spec 05 §2 normalisation, spec 04b §4.1 routing). Pure, thread-safe. */
object SenderIds {
    private val DLT_FULL = Regex("(?i)^([a-z]{2}-)([a-z]{4,6}|[0-9]{6})-([a-z]+)$")
    private val DLT_LEN8 = Regex("(?i)^([a-z]{5,6}|[0-9]{6})-([STGP]+)$")
    private val DLT_LEN11 = Regex("(?i)^([a-z]{2}-)([a-z0-9]{6})-([STGP]+)$")
    private val OPERATOR_PREFIX = Regex("^([A-Za-z]{2})-(.+)$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val INDIAN_MOBILE = Regex("^[6-9]\\d{9}$")
    private val PHONE_CHARS = Regex("[\\s()\\-.]")

    fun parse(address: String?): SenderInfo {
        val raw = address?.trim().orEmpty()
        if (raw.isEmpty()) {
            return SenderInfo(raw, SenderType.UNKNOWN, "", null, DltSuffix.NONE, isBusiness = false, isServiceStyle = false)
        }
        val isBusiness = isBusiness(raw)
        if (EMAIL.matches(raw)) {
            return SenderInfo(raw, SenderType.EMAIL, raw.lowercase(), null, DltSuffix.NONE, isBusiness, false)
        }
        if (raw.any { it.isLetter() } && !raw.contains(',')) return parseHeader(raw, isBusiness)

        val digits = raw.filter { it.isDigit() }
        val compact = raw.replace(PHONE_CHARS, "")
        val indianShort = isIndianShortNumber(compact)
        val shortCode = (!compact.startsWith("+") && digits.isNotEmpty() && digits.length < 9 &&
            compact.all { it.isDigit() }) || indianShort
        return if (shortCode) {
            val id = if (indianShort) digits.removePrefix("91") else digits
            SenderInfo(raw, SenderType.SHORT_CODE, id, null, DltSuffix.NONE, isBusiness, isServiceStyle = id.length == 6)
        } else {
            SenderInfo(raw, SenderType.PHONE_NUMBER, raw, null, DltSuffix.NONE, isBusiness, false)
        }
    }

    /** `ML.g`: is this a business / header sender (spec 04b §4.1 step 2, spec 02 §4.3 step 1)? */
    fun isBusiness(address: String): Boolean {
        val a = address.trim()
        if (a.isEmpty() || a.startsWith("+")) return false
        if (a.contains(',')) return a.count { !it.isLetter() } < 15
        if (a.any { it.isLetter() }) return true
        val digits = a.filter { it.isDigit() }
        return digits.isNotEmpty() && digits.length < 9
    }

    /**
     * An Indian-looking number (`+91` / `91` prefix) whose national part is 6–8 digits: treated as a
     * short code on the phone path (spec 04b §4.3 step 2b).
     */
    fun isIndianShortNumber(address: String): Boolean {
        val a = address.replace(PHONE_CHARS, "")
        if (INDIAN_MOBILE.matches(a)) return false // a bare 10-digit mobile that happens to start with 91
        val national = when {
            a.startsWith("+91") -> a.substring(3)
            a.startsWith("91") && a.length in 8..9 -> a.substring(2)
            else -> return false
        }
        return national.length in 6..8 && national.all { it.isDigit() }
    }

    /**
     * Canonical 6-character header id (spec 05 §2 step 1): `$-`→`S-`, strip the DLT suffix, strip the
     * operator prefix, and keep the last 6 of an 8-character prefix-less header.
     */
    fun canonicalHeader(address: String): String = parseHeader(address.trim(), true).canonicalId

    private fun parseHeader(raw: String, isBusiness: Boolean): SenderInfo {
        var a = raw.replace("$-", "S-").replace(" ", "")
        var suffix: Char? = null
        DLT_FULL.matchEntire(a)?.let { m ->
            suffix = m.groupValues[3].first()
            a = m.groupValues[1] + m.groupValues[2]
        }
        if (a.length == 8) DLT_LEN8.matchEntire(a)?.let { m ->
            suffix = m.groupValues[2].first()
            a = m.groupValues[1]
        }
        if (a.length == 11) DLT_LEN11.matchEntire(a)?.let { m ->
            suffix = m.groupValues[3].first()
            a = m.groupValues[1] + m.groupValues[2]
        }
        var prefix: String? = null
        OPERATOR_PREFIX.matchEntire(a)?.let { m ->
            prefix = m.groupValues[1].uppercase()
            a = m.groupValues[2]
        }
        // An 8-letter header without "-" is "XXYYYYYY" (spec 04b §4.2: rewritten XX-YYYYYY).
        if (prefix == null && a.length == 8 && !a.contains('-') && a.take(2).all { it.isLetter() }) {
            prefix = a.take(2).uppercase()
            a = a.takeLast(6)
        }
        val id = a.uppercase()
        val numeric = id.isNotEmpty() && id.all { it.isDigit() }
        val serviceStyle = numeric && (prefix != null || id.length == 6)
        return SenderInfo(raw, SenderType.ALPHANUMERIC, id, prefix, DltSuffix.of(suffix), isBusiness, serviceStyle)
    }
}
