// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/OtpExtractor.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/** A one-time password found in a message (spec 05 §7.1, §7.3). */
data class OtpResult(
    /** The code as it must be typed (digits, or upper-case letters / alphanumerics for special formats). */
    val code: String,
    /** The gate keyword that marked the message as an OTP ("verification code", "otp", …), lower case. */
    val keyword: String,
    /** Notification title suffix: "OTP" or "OTP to <verb>" (spec 05 §7.3 e). */
    val purpose: String,
    /** Payment amount (digits, `.` decimal) when the OTP authorises a payment (category 34), else null. */
    val paymentAmount: String? = null,
) {
    /** Display format: a space after every character ("1 2 3 4 5 6", spec 05 §7.3 f). */
    val displayCode: String get() = OtpExtractor.spaced(code)

    /** OTP inside a payment / transaction-like message (category 34). */
    val isPayment: Boolean get() = paymentAmount != null
}

/**
 * OTP detection and extraction (spec 05 §7.3). Detection only: the notification UI is Task 12.
 *
 * Algorithm: (c) special formats first; (a) keyword gate; (b) normalise a lower-case copy so every
 * digit run that is not the code (amounts, dates, phone numbers, card masks, times, call instructions…)
 * is blanked; (d) ordered digit rules, each returning the first capture with 4–10 digits (rule 15
 * accepts 3). Lists come from 05a (FOI, FOIS, ERRC, VEOP) with built-in fallbacks.
 */
class OtpExtractor(lists: KeywordLists = KeywordLists.EMPTY) {

    private val foi = lists.item("getStringFOI", 0).ifBlank { DEFAULT_FOI }
    private val fois = lists.item("getStringFOIS", 0).ifBlank { DEFAULT_FOIS }
    private val errc = lists.item("getStringERRC", 0).ifBlank { DEFAULT_ERRC }
    private val veop = lists.item("getOTPVerb", 0).ifBlank { DEFAULT_VEOP }

    private val gate1 = Regex("(?i)$foi\\s*$fois")
    private val gate2 = Regex("(?i)(otp|code|mpin|apple id)([^a-z]|$)")
    private val kwTrail = Regex("(?i)\\s*(\\((otp|otac|code)\\)|-\\s*(otp|otac))\\s*$")
    private val errGuard = Regex("(?i)$errc|(off\\W)|(@\\s*(\\d))|buy")
    private val verbNear = Regex("(?i)\\b(?:to|for)\\s+(?:\\w+\\s+)??($veop)\\b")
    private val oneTimePassword = Regex("(?i)one(\\W+|)time(\\W+|)password")

    /** Extracts the OTP of [body] from [sender], or null if the message carries none. */
    fun extract(body: String, sender: String = ""): OtpResult? {
        if (body.isBlank()) return null
        val special = specialCase(body, sender)
        val keyword = special?.second ?: gateKeyword(body) ?: return null
        // Gate passed only on a generic "code" and the body reads like an offer / error code: not an OTP.
        if (special == null && keyword == "otp" && !OTP_WORD.containsMatchIn(body) && errGuard.containsMatchIn(body)) {
            return null
        }
        val code = special?.first ?: digitRules(body, keyword) ?: return null
        return OtpResult(code, keyword, purpose(body, code), paymentAmount(body))
    }

    /**
     * True if [body] names an OTP explicitly ("OTP", "one time password", "verification code", "2FA",
     * an SMS-retriever "<#" prefix…), not just any "code" / "password". Used to run OTP extraction
     * ahead of the alert shortcuts on business senders and as the only OTP gate for phone-number senders
     * (a contact's "gate code is 4521" or "wifi password is 12345678" is not an OTP).
     */
    fun hasStrongKeyword(body: String): Boolean = STRONG_KEYWORD.containsMatchIn(body)

    /**
     * Service-code cue for phone-number senders that are not personal: a brand name of a service that sends
     * login codes from long numbers (WhatsApp, Apple ID, Instagram, Uber…), or an affirmative "your / the
     * <brand> code is 1234" / "1234 is your <brand> code" phrase. A plain "the gate code is 4521" has no brand
     * and is not a cue.
     */
    fun hasServiceCodeCue(body: String): Boolean =
        SERVICE_BRAND.containsMatchIn(body) || AFFIRMATIVE_CODE.containsMatchIn(body)

    /** True if [body] passes the keyword gate (it talks about an OTP / code / PIN). */
    fun mentionsOtp(body: String): Boolean = gateKeyword(body) != null

    // ------------------------------------------------------------------ (a) gate

    private fun gateKeyword(body: String): String? {
        gate1.find(body)?.let { return it.value.lowercase().replace(kwTrail, "").trim() }
        if (gate2.containsMatchIn(body)) return "otp"
        return null
    }

    // ------------------------------------------------------------------ (c) special formats

    private fun specialCase(body: String, sender: String): Pair<String, String>? {
        if (body.trimStart().startsWith("<#") || body.contains("[#")) {
            RETRIEVER.find(body)?.let { m ->
                val c = m.groups[3]?.value ?: m.groups[5]?.value
                if (c != null && c.length in 4..7) return c to "otp"
            }
        }
        if (sender.contains("GST India", ignoreCase = true) || sender.contains("GSTIND", ignoreCase = true)) {
            GST_EVC.find(body)?.let { return it.groupValues[3] to "otp" }
        }
        AUTHENTICATE_8.find(body)?.let { return it.groupValues[1] to "otp" }
        if (sender.contains("swiggy", ignoreCase = true)) {
            SWIGGY.find(body)?.takeIf { !it.value.contains('.') }?.let { return it.groupValues[3] to "code" }
        }
        if (body.contains("apple id", ignoreCase = true)) {
            APPLE_ID.find(body)?.groupValues?.get(1)?.takeIf { it.length in 4..10 }?.let { return it to "apple id" }
        }
        return null
    }

    // ------------------------------------------------------------------ (b) + (d)

    private fun digitRules(body: String, keyword: String): String? {
        val t = normalise(body)
        val k = Regex.escape(keyword)
        val kr = ruleCache.getOrPut(keyword) { KeywordRules(buildRules(k), buildLateRules(k)) }
        for ((i, rule) in kr.ordered.withIndex()) {
            val source = if (i == WHATSAPP_INDEX) body.lowercase() else t
            val found = firstValid(rule.first, source, rule.second, 4..10)
            if (found != null) return found.replace("-", "")
        }
        // 15: the only rule that accepts 3 digits.
        firstValid(kr.threeDigit, t, 1, 3..3)?.let { return it }
        // 16: generic "code 1234" / "otp 1234" only when the body has no offer / error-code wording.
        if (!errGuard.containsMatchIn(body)) {
            firstValid(CODE_IS, t, 2, 4..10)?.let { return it }
            // "123 456 is your Instagram code" (the 3+3 digits were joined by normalise).
            firstValid(IS_YOUR_CODE, t, 1, 4..10)?.let { return it }
            firstValid(OTP_DIGITS, t, 2, 4..10)?.let { return it }
        }
        // 17: upper-case letter code on the raw body.
        kr.upperCode.find(body)?.let { return it.groupValues[1] }
        firstValid(kr.toLink, t, 1, 4..10)?.let { return it }
        firstValid(kr.dashed, t, 3, 4..10)?.let { return it }
        return null
    }

    /** Compiled rules for one gate keyword (rules 1–14 in order, plus 15, 17, 18, 19). */
    private class KeywordRules(val ordered: List<Pair<Regex, Int>>, late: List<Regex>) {
        val threeDigit = late[0]
        val upperCode = late[1]
        val toLink = late[2]
        val dashed = late[3]
    }

    private val ruleCache = java.util.concurrent.ConcurrentHashMap<String, KeywordRules>()

    private fun buildLateRules(k: String): List<Regex> = listOf(
        Regex("(?i)(?<!\\d)([0-9]{3})\\sis(\\W|\\w){0,20}(otp|$k)"),
        Regex("\\b([A-Z]{5,10})\\b(?i)(\\p{Punct}|\\s*)\\s*(is|as)\\s*(your|the|a)?.*?(otp|$k)"),
        Regex("(?i)(\\d{4,9})\\s*(:|\\s*|-)\\s*(is|as|)\\s*\\W+(otp|$k)\\sto.*?link"),
        Regex("(?i)your\\s(otp|$k)\\s(\\w+){4}-(\\d{4,9})"),
    )

    /** Rules 1–14 of spec 05 §7.3 d for the escaped keyword [k], with the capture group of each. */
    private fun buildRules(k: String): List<Pair<Regex, Int>> = listOf(
        "(?<!\\d)(\\d+)(\\p{Punct}|\\s*)\\s*(is|as)\\s*(your|the|a)\\s*\\W*?.+?(otp|$k)" to 1,
        "(otp|$k)(\\scode|)\\s*(for|to)(.*?[\\n\\r].*?|.*?)\\s*(is)(\\W+)(\\d+)" to 7,
        "(?<!\\d)(\\d+)\\s*(:|\\s*|-)\\s*(is|as)\\s*\\W*?.+?(otp|$k)" to 1,
        "(?<!\\d)(\\d+)\\s*as\\s*(your|\\s*|the|a)\\s*\\W*?(otp|$k)" to 1,
        "$k\\s*.+?\\s*(?<!\\d)(\\d+)" to 1,
        "use\\s*$k\\s*(\\d+)" to 1,
        "(?<!\\d)(\\d+)\\s*.+?\\s*$k" to 1,
        "(?<!\\d)(\\d+)\\s*use\\s*.+?(otp|$k)\\s*for" to 1,
        "$k\\s*\\p{Punct}\\s*(\\d+)" to 1,
            WHATSAPP_RULE to 3,
        "(otp)\\s*(\\s*|\\p{Punct})\\s*(\\d+).*?to" to 3,
        "otp\\s*alert.*?use\\s*(\\d+)" to 1,
        "(otp|$k|for)(.*?[\\n\\r].*?|.*?)(:|\\n)(\\W+|)(\\d+)(\\W+|$)" to 5,
        "(otp|$k)\\W+(\\w+|)(\\W+|)(\\w+|)(\\W+|)is\\W+(\\d+)" to 6,
    ).map { (pattern, group) -> Regex("(?i)$pattern") to group }

    private fun firstValid(regex: Regex, text: String, group: Int, digits: IntRange): String? {
        for (m in regex.findAll(text)) {
            val v = m.groups[group]?.value ?: continue
            if (v.count { it.isDigit() } in digits && v.all { it.isDigit() || it == '-' }) return v
        }
        return null
    }

    /** Lower-case copy with every non-code digit run blanked (spec 05 §7.3 b). Exposed for tests. */
    internal fun normalise(body: String): String {
        var t = body.lowercase()
        t = t.replace(URL, " ").replace(EMAIL, " ").replace(INTL_PHONE, " ")
        for (l in LinkExtractor.extract(t)) t = t.replace(l.text.lowercase(), " ")
        t = JOIN_3_3.replace(t) { m -> m.groupValues[1] + m.groupValues[2].replace(" ", "") + m.groupValues[5] }
        t = t.replace("(otp)", "otp")
        for (r in BLANKS) t = r.replace(t, " ")
        // Phone / account numbers: every run of 10+ digits (the code is at most 9 digits after this).
        t = LONG_DIGITS.replace(t, " ")
        return t
    }

    // ------------------------------------------------------------------ (e) purpose, payment

    private fun purpose(body: String, code: String): String {
        val sentence = body.split(SENTENCE).firstOrNull { it.contains(code) } ?: body
        val verb = verbNear.find(sentence)?.groupValues?.get(1)
            ?: verbNear.find(body)?.groupValues?.get(1)
        val label = if (verb != null) "OTP to ${verb.lowercase()}" else "OTP"
        return label.replace(oneTimePassword, "OTP")
    }

    private fun paymentAmount(body: String): String? {
        if (!PAYMENT_WORDS.containsMatchIn(body)) return null
        val m = AMOUNT.find(body) ?: return null
        return m.groupValues[1].replace(",", "").trimEnd('.').takeIf { it.any { c -> c.isDigit() && c != '0' } }
    }

    companion object {
        const val DEFAULT_FOI = "(security|authentication|reset|activation|access|one time|onetime|one-time|" +
            "verification|hike|music|wifi|confirmation|netsecure|secret|login|pass|auth|one time authorization|" +
            "onetime authorization|one-time authorization|delivery|account)"
        const val DEFAULT_FOIS = "(code|password|pin|mobile token|token)"
        const val DEFAULT_ERRC = "(offer|discount|promo|pincode|country code|error code|exclusively|cashback|" +
            "book now|pin code|otp not working|incorrect entry|rewarding|coupon code|voucher)"
        const val DEFAULT_VEOP = "login|log in|sign in|signin|verify|reset|change|confirm|register|pay|access|" +
            "unlock|activate|deliver|transfer|update|link|add|complete|proceed"

        /** "123456" → "1 2 3 4 5 6". */
        fun spaced(code: String): String = code.toCharArray().joinToString(" ")

        private val RETRIEVER = Regex("((is)\\s(\\d{3,7}))|((\\d{3,7})\\s(is|as|to))")
        private val GST_EVC = Regex("((?i)otp\\sfor.*?)EVC\\s([iIsS]+\\s|\\s*)([A-Z0-9]{6})")
        private val AUTHENTICATE_8 = Regex("(?i)otp\\sto\\sauthenticate.*?is\\s([A-Z0-9]{8})\\W")
        private val SWIGGY = Regex("(?i)share.*?code.*?after.*?order(\\W+|):(\\W+|)([0-9]{4,8})")
        private val APPLE_ID = Regex("(?i)apple id.*?:\\s*(\\d+)")
        private const val WHATSAPP_INDEX = 9
        private val STRONG_KEYWORD = Regex(
            "(?i)\\b(otp|otac|mpin|one\\W?time\\W?(password|passcode|pin|code)|" +
                "(verification|verify|authentication|auth|security|login|sign-?in)\\s+code|" +
                "passcode|2fa|two[- ]?(factor|step))\\b|^\\s*<#|\\[#\\]",
        )
        private const val BRANDS = "whatsapp|apple\\s*id|telegram|instagram|facebook|google|microsoft|uber|ola|" +
            "swiggy|zomato|amazon|flipkart|paytm|phonepe"
        private val SERVICE_BRAND = Regex("(?i)\\b($BRANDS)\\b")
        private val AFFIRMATIVE_CODE = Regex(
            "(?i)\\b(your|the)\\s+(($BRANDS)\\s+)?code\\s*(is)?\\s*:?\\s*\\d|" +
                "\\d[\\d -]{2,}\\s+is\\s+(your|the)\\s+(($BRANDS)\\s+)?code\\b",
        )
        private val OTP_WORD = Regex("(?i)\\b(otp|mpin|one.?time)")
        private val IS_YOUR_CODE = Regex("(?i)(?<!\\d)(\\d+)\\s*is\\s+(your|the)\\s+(\\w+\\s+){0,2}code\\b")
        private val CODE_IS = Regex("(?i)code\\s*(is|\\p{Punct}|)\\s*(\\d+)")
        private val OTP_DIGITS = Regex("(?i)(otp|mpin|login)\\W+\\s*(\\d+)")
        private val INTL_PHONE = Regex("\\+\\d{1,3}[\\s-]*(\\d[\\s-]*){9,11}")
        private const val WHATSAPP_RULE = "whatsapp\\s+code\\s*(\\s*|is)(\\W+)(\\d+-\\d+)"

        private val URL = Regex("(?i)(https?://|www\\.)\\S+")
        private val EMAIL = Regex("\\S+@\\S+\\.\\S+")
        private val JOIN_3_3 = Regex("(\\W|^)((\\d){3}\\s(\\d){3})(\\W|$)")
        private val LONG_DIGITS = Regex("\\d{10,}")
        private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|" +
            "december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"

        /** Spec 05 §7.3 b, in order. Call/SMS instructions do not cross other digits (interpretation). */
        private val BLANKS = listOf(
            // Security warnings ("do not share your OTP"): their keyword must not anchor a digit rule.
            "(do not|don'?t|dont|never)\\s+(share|disclose|tell|give)[^.\\n]{0,40}(otp|pin|password|cvv)",
            // Reference / transaction / order ids are never the code.
            "\\b(ref(erence)?(\\s*no)?|txn(\\s*(id|no))?|utr|rrn|transaction\\s*(id|no)|customer\\s*id|" +
                "order(\\s*(no|id))?|awb|invoice(\\s*no)?|booking\\s*id)\\W*\\d+",
            "^resend\\W+[0-4]:",
            "booking\\s*\\d+\\s*con\\s*\\d+",
            "booking\\s*(no|id|ref)\\W*\\d+",
            "card\\s*(no\\.?\\s*)?[x*.]{2,}\\s*\\d+",
            "\\*\\d+\\*\\d+#", "\\*\\d+#",
            "\\$\\s*[\\d,]+(\\.\\d+)?",
            "\\d+(,\\d+)+(\\.\\d+)?",
            "(?:\\b(?:rs|inr|usd|aed)|₹)\\.?\\s*[\\d,]+(\\.\\d+)?",
            "\\d+(\\.\\d+)?\\s*(rs|rupees|inr)\\b",
            "\\d+\\.\\d+",
            "\\bfrom\\s*\\d+",
            "(card|account|acc|a/c)\\s*(no\\.?|number)?\\s*(ending|ends|end|with)\\s*(with|in)?\\W*\\d+",
            "\\b(application|number|no)\\s*[.:#-]?\\s*\\d+",
            "\\d{1,2}(st|nd|rd|th)?\\W{0,2}($MONTHS)[a-z]*\\W{0,2}(\\d{2,4})?",
            "\\b($MONTHS)[a-z]*\\W{0,2}\\d{1,2}(\\W{1,2}\\d{2,4})?",
            "\\d{4}-\\d{1,2}-\\d{1,2}",
            "\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}",
            "\\d+-\\d+-\\d+",
            "\\d+/-",
            "\\+\\d+(\\W|$)",
            "\\d+\\s*%",
            "\\d+:\\d+(:\\d+)?",
            "\\d{3}(-|\\s)\\d{5,12}",
            "(sms|msg|message)\\b[^\\d\\n]{0,30}?\\bto\\s*\\d+",
            "(send|call)\\b[^\\d\\n]{0,30}?\\b(to|on)\\s*\\d+",
            "unique calling code\\s*\\d+",
            "call by dialing\\s*\\d+(-\\d+)?",
            "dial\\s*\\d+",
            "call\\s*\\p{Punct}?\\s*\\d+",
            "bhej(\\s*de)?\\s*\\d+",
            "bhejiye\\s*\\d+",
            "\\d+#+", "#+\\d+", "\\d+\\*+", "\\*+\\d+",
            "\\bno\\s*([-:=])\\s*\\d+",
            "\\b(expire|expiring|expires|in|on|within)\\s*\\d+",
            "never\\sshare\\s(your\\s|)(pin\\W+otp|otp\\W+pin|otp|pin)",
            "(?:[a-z]+[0-9]+|[0-9]+[a-z]+)[a-z0-9]*",
            "(?<!\\d)\\d{1,2}(?!\\d)",
        ).map { Regex(it) }

        private val SENTENCE = Regex("(?<=[.!?\\n])\\s+|\\n")
        private val PAYMENT_WORDS = Regex(
            "(?i)\\b(pay|payment|paying|txn|transaction|purchase|debit|debited|spend|spent|merchant|" +
                "e-?commerce|card ending)\\b",
        )
        private val AMOUNT = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)")
    }
}
