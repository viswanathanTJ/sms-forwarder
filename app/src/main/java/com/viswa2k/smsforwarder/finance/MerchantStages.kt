// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/MerchantStages.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.KeywordLists

/**
 * Stage F (`BankWhereAtDetails`, spec 05 §3.9: merchant and payment mode), Stage I (`BankCheckForMore`,
 * §3.12: payee and UPI handle) and the `j()` follow-ups (payee fallbacks, UPI ref, netbanking).
 */
internal class MerchantStages(lists: KeywordLists) {

    // ------------------------------------------------------------------ Stage F

    private val F1_DOMAIN = Regex("(?i)(\\W(at|towards))\\W+([a-zA-z]|\\+|-).*?(\\.(com|in|org|gov|edu|io|co))([\\W])")
    private val F1_CLEAN = Regex("(?i)(\\.(com|in|org|gov|edu|io|co)|,|-|txn|www\\.)")
    private val F2_MODE = Regex("(?i)(txn|atm\\s|cash[\\W]|pos |neft|rtgs|fund(s|) transfer| ft|net\\s*banking|imps|cheque|chq)")
    private val F2_CHEQUE_CLEARING = Regex("(?i)(cheque|chq)(.*?)clearing")
    private val F3_DETAILS = Regex("(?i)details\\:\\-(\\W|)(.*?)\\.\\(")
    private val F4_FOR = Regex("((?i)(\\Wfor(:|)|info:)\\s*((?!using|rs|inr|your|otp|neft|rtgs)))[A-Z].*?(\\.|,|:|_|(?i)(txn|trans)|$)")
    private val F4_STRIP = Regex("(?i)(^\\W*for:?|\\bfrom\\b|info:|[,_]|-|txn)")
    private val F5_CARD = Regex("(?i)x{2,}[0-9]+[a-zA-z\\s]+Debited\\s+for\\s+(.*?)\\sfor\\s(rs|inr)(\\W+|)[0-9]")
    private val F6_DATE = Regex("(?i)(\\W)on\\s*(\\W+|)[0-9]+")
    private val F7_INWARD = Regex("(?i)\\W(cr\\W+INW)\\W+(.*?(\\.|_|,|:|$| (has|was|will|is|with|on) ))")
    private val F8_AT = Regex("((?i)\\W(at|towards)(\\scf|\\sombk|))\\W+([a-zA-z]|\\+|-).*?(\\.|_|,|:|$|(?i) (has|was|will|is|with|on) )")
    private val F8_TERM = Regex("(?i)(\\.|_|,|:| (has|was|will|is|with|on) )$")
    private val F8_CLEAN = Regex("(?i)(^\\W*(at|towards)(\\scf|\\sombk|)\\W+|\\.|,|_|-|:)")
    private val F9_MODE = Regex("(?i)(NACH|atm\\W|cash[\\W]|upi|neft|rtgs|fund(s|) transfer| ft|net\\s*banking|imps|\\Wpos\\W|cheque|chq|vpa)")
    private val F9_TRANSFER = Regex("(?i)(transferred|transfer\\sfrom)")
    private val F9_WITHDRAW = Regex("(?i)(withdrawn|withdrawal)")
    private val CARD_PHRASES = listOf(Regex("(?i)(credit|debit)\\s*card"), Regex("(?i)card(\\W+|)to\\s*card"))

    fun stageF(s: ParseState) {
        val w = s.w
        // 1. Domain merchant.
        F1_DOMAIN.find(w)?.takeIf { it.value.length < 25 }?.let { m ->
            var v = m.value.replace(Rx.AT, " ").replace(Rx.TOWARDS, " ")
            v = F1_CLEAN.replace(v, " ").replace(Rx.SPACES, " ").trim()
            if (v.isNotEmpty()) { s.merchant = v; s.rule += "F1 " }
        }
        // 2. Mode word as placeholder (see TransactionParser doc: kept as a last-resort merchant).
        if (s.merchant.isEmpty()) {
            F2_MODE.find(w)?.let { m ->
                val word = m.value.trim().trimEnd { !it.isLetterOrDigit() }.lowercase()
                val placeholder = when {
                    word == "cheque" || word == "chq" -> if (F2_CHEQUE_CLEARING.containsMatchIn(w)) "cheque" else ""
                    else -> word
                }
                s.merchantPlaceholder = placeholder
            }
        }
        // 3–5.
        if (s.merchant.isEmpty()) F3_DETAILS.find(w)?.let { s.merchant = it.groupValues[2].trim(); s.rule += "F3 " }
        if (s.merchant.isEmpty()) F4_FOR.find(w)?.let { m ->
            val v = F4_STRIP.replace(m.value, " ").replace(Rx.SPACES, " ").trim().trimEnd('.', ':')
            if (v.length >= 2) { s.merchant = v; s.rule += "F4 " }
        }
        if (s.merchant.isEmpty()) F5_CARD.find(w)?.let { m ->
            s.merchant = m.groupValues[1].trim().split(Rx.SPACES).take(3).joinToString(" ")
            s.rule += "F5 "
        }
        // 6. Cut a trailing date.
        if (s.merchant.isNotEmpty()) F6_DATE.find(s.merchant)?.let { s.merchant = s.merchant.substring(0, it.range.first).trim() }
        // 7. Inward credit.
        if (s.merchant.isEmpty() || s.merchant.equals("txn", true)) F7_INWARD.find(w)?.let { m ->
            s.merchant = m.groupValues[2].replace(F8_TERM, "").trim().trimEnd('.', ',', ':', '_')
            s.rule += "F7 "
        }
        // 8. General "at / towards".
        if (s.merchant.isEmpty()) {
            for (m in F8_AT.findAll(w)) {
                var v = F8_TERM.replace(m.value, "")
                v = F8_CLEAN.replace(v, " ").replace(Rx.SPACES, " ").trim()
                if (v.isEmpty() || v.lowercase().startsWith("http")) continue
                F6_DATE.find(" $v")?.let { d -> v = " $v".substring(0, d.range.first).trim() }
                if (v.isEmpty()) continue
                s.merchant = v
                s.rule += "F8 "
                break
            }
        }
        // 9. Payment mode.
        val mode = F9_MODE.find(w)
        if (mode != null) {
            val word = mode.value.trim { !it.isLetterOrDigit() && it != ' ' }.trim().lowercase()
            s.paymentMode = when {
                word == "chq" || word == "cheque" -> "Cheque"
                word == "ft" -> "CREDITED"
                word.startsWith("atm") -> "ATM"
                word.startsWith("cash") -> { s.modeKnown = true; "CASH" }
                word == "pos" -> { s.modeKnown = true; "POS" }
                word == "vpa" || word == "upi" -> "UPI"
                word.startsWith("net") -> "Net Banking"
                word.startsWith("fund") -> "Fund Transfer"
                else -> word.uppercase()
            }
        } else if (F9_TRANSFER.containsMatchIn(w)) {
            s.modeKnown = true
        } else if (F9_WITHDRAW.containsMatchIn(w)) {
            s.paymentMode = "ATM"
        }
        // Before Stage H the residual text loses the card phrases.
        for (re in CARD_PHRASES) s.w = re.replace(s.w, " ")
    }

    // ------------------------------------------------------------------ Stage I

    private val upiHandles = lists.item("getStringForUpiId", 0).ifBlank { DEFAULT_UPI }
    private val I_VPA_CTX = Regex("((?i)(\\W|\\w){0,15})\\Wvpa\\W")
    private val I_RULES: List<Pair<String, Regex>> = listOf(
        "pos" to Regex("(?i)\\W(by|from)\\W+pos\\W+(.*?)\\s"),
        "trf" to Regex("(?i)\\W+(trf|transfer)\\:+(.*?)(\\:|\\Won\\W)"),
        "neftcrdr" to Regex("(?i)\\W(for|towards)\\sneft\\s(cr|dr)\\s*\\-.*?\\-(.*?)\\-"),
        "neft" to Regex("(?i)(\\Wfor|info:)(\\W+|)(neft|rtgs)\\W+(.*?)\\."),
        "benf" to Regex("((?i)\\Wfrom\\sbeneficiary)\\W+([A-Z].*?)\\."),
        "neftutr" to Regex("((?i)\\W(by|from))\\W+neft\\/(.*?)utr"),
    )
    private val UPI_AT = Regex("(?i)([a-zA-z0-9.-]+)[@_!]($upiHandles)\\b")
    private val UPI_VPA = Regex("(?i)\\Wvpa\\W+([a-zA-z0-9.-]+)(@[a-zA-z]+)")
    private val UPI_BY = Regex("(?i)by\\s([a-zA-z0-9.-]+)[@_!][a-zA-z]+\\W")
    private val UPI_MOBILE = Regex("(?i)([0-9]{10}(-\\d+|))([@_!][a-zA-z]+)")
    private val UPI_AT_UPI = Regex("(?i)\\W\\@upi\\_(.*?)(\\s\\W|\\W\\s|[0-9]{2})")
    private val UPI_SLASH = Regex("(?i)\\Wupi\\/(.*?)(\\.|,|-|:|\\_|(?i)(txn|trans|\\Wby)|$)")
    private val UPI_LONG = Regex("\\Wupi[-\\/][0-9]{11,}[-\\/](.*?)\\.")
    private val UPI_DIGITS_AT = Regex("(?i)[0-9]{2}\\W\\@(.*?)(\\s[0-9])")
    private val BANK_RULES: List<Pair<String, Regex>> = listOf(
        "hdfc" to Regex("(?i)(HSBC|HDFC).*?\\W(to|towards)(\\W+|)((?!using|rs|inr|your|otp|neft)(beneficiary\\W+|))\\s*(.*?)(\\.|,|:|___|(?i)\\W(on|from|utr|ref|for\\W)|\\Wvia|$)"),
        "bob" to Regex("(?i)\\Wat\\W(upi|)(\\W|)([a-zA-Z0-9]+)\\W([a-zA-Z0-9]+).*?(\\Won|)"),
        "idbi" to Regex("(?i)idbi.*?\\sdet\\W+(.*?)\\."),
    )
    private val OTHER_RULES: List<Pair<String, Regex>> = listOf(
        "nach" to Regex("(?i)NACH\\sdr\\W(.*?)\\Won"),
        "spay" to Regex("(?i)your\\s(Spay.*?)\\sbill"),
        "billpaid" to Regex("(?i)bill\\spaid\\W+(.*?\\sbill)\\s\\d"),
        "impsx" to Regex("(?i)x{3,}\\d+\\-(.*?)\\(imps"),
        "forimps" to Regex("((?i)\\Wfor\\s)imps\\W+([A-Z]+.*?)-(\\W+|)[0-9]{4}"),
        "imps/" to Regex("imps\\/.*?\\/[0-9]+\\/(.*?)\\s"),
        "neftcr" to Regex("(?i)\\s-\\sneft\\scr\\s(\\w+\\s\\w+)"),
        // Rebuild addition (SBI "… trf to NAME Refno …"; the original's undecompiled l() may have covered it).
        "trfto" to Regex("(?i)\\Wtrf\\s+to\\s+(.*?)\\s+(ref|refno|ref\\s*no|upi|on|via)\\b"),
    )
    private val AUTO_DEBIT = Regex("(?i)via\\W+auto(\\W+|)debit")

    private fun lastGroup(m: MatchResult, index: Int): String = m.groupValues.getOrElse(index) { "" }.trim()

    /** Stage I: returns the first payee found (and records the VPA); the merchant only when F found none. */
    fun stageI(s: ParseState) {
        val w = s.w
        val text = s.text
        var payee = ""
        fun take(v: String, rule: String) {
            if (payee.isEmpty()) {
                val c = v.trim().trimEnd('.', ',', ':', '-', '_').trim()
                if (c.isNotEmpty()) { payee = c; s.rule += "I:$rule " }
            }
        }
        I_VPA_CTX.find(w)?.let { m ->
            val ctx = m.groupValues[1]
            val follow = FinanceText.after(w, m.range.last + 1, 40).lowercase()
            if (!("your" in ctx.lowercase() && "to" in follow)) {
                // The 15 chars before "VPA": drop a cut leading word and connector words.
                val words = ctx.trim().split(Rx.SPACES).let { if (ctx.firstOrNull()?.isWhitespace() == false) it.drop(1) else it }
                val v = words.dropWhile { it.lowercase() in VPA_CONNECTORS || it.any(Char::isDigit) }.joinToString(" ")
                if (v.isNotBlank() && v.none(Char::isDigit)) take(v, "vpactx")
            }
        }
        for ((name, re) in I_RULES) {
            if (payee.isNotEmpty()) break
            re.find(w)?.let { m ->
                take(when (name) {
                    "pos" -> m.groupValues[2]
                    "trf" -> m.groupValues[2]
                    "neftcrdr" -> m.groupValues[3]
                    "neft" -> m.groupValues[4]
                    "benf" -> m.groupValues[2]
                    else -> m.groupValues[3]
                }, name)
            }
        }
        // UPI handles (on the normalised text: Stage A may have eaten nothing from a VPA, but W loses case-free tokens).
        val vpaMatch = UPI_AT.find(text)
        if (vpaMatch != null) {
            s.vpa = vpaMatch.value.lowercase()
            take(cleanHandle(vpaMatch.groupValues[1]), "upi@")
        }
        UPI_VPA.find(text)?.let { m ->
            if (s.vpa == null) s.vpa = (m.groupValues[1] + m.groupValues[2]).lowercase()
            take(cleanHandle(m.groupValues[1]), "vpa")
        }
        UPI_BY.find(text)?.let { take(cleanHandle(it.groupValues[1]), "upiby") }
        UPI_MOBILE.find(text)?.let { m ->
            if (s.vpa == null) s.vpa = m.value.lowercase()
            take(m.groupValues[1].substringBefore('-'), "upimobile")
        }
        UPI_AT_UPI.find(text)?.let { take(cleanHandle(it.groupValues[1]), "@upi_") }
        UPI_SLASH.find(text)?.let { take(cleanHandle(it.groupValues[1]), "upi/") }
        UPI_LONG.find(text)?.let { take(cleanHandle(it.groupValues[1]), "upilong") }
        UPI_DIGITS_AT.find(text)?.let { take(cleanHandle(it.groupValues[1]), "digits@") }
        val lowerSender = (s.sender.name + " " + s.sender.id).lowercase()
        for ((name, re) in BANK_RULES) {
            if (payee.isNotEmpty()) break
            val applies = when (name) {
                "hdfc" -> "hdfc" in lowerSender || "hsbc" in lowerSender
                "bob" -> "baroda" in lowerSender || "bob" in lowerSender
                else -> "idbi" in lowerSender
            }
            if (!applies) continue
            re.find(if (name == "hdfc") "${s.sender.name} $w" else w)?.let { m ->
                take(when (name) {
                    "hdfc" -> lastGroup(m, 6)
                    "bob" -> lastGroup(m, 3) + " " + lastGroup(m, 4)
                    else -> lastGroup(m, 1)
                }, name)
            }
        }
        for ((name, re) in OTHER_RULES) {
            if (payee.isNotEmpty()) break
            re.find(w)?.let { m ->
                take(when (name) {
                    "forimps" -> m.groupValues[2]
                    else -> m.groupValues[1]
                }, name)
            }
        }
        if (AUTO_DEBIT.containsMatchIn(w)) s.paymentMode = "Auto-debit"
        if (s.merchant.isEmpty() && payee.isNotEmpty()) s.merchant = payee
    }

    /** Handle clean-up (spec 05 §3.12): `-[0-9]+$`, `__+`, digits after `q`, "paytm"/"paytmqr". */
    private fun cleanHandle(h: String): String {
        var v = h.trim()
        v = v.replace(Rx.DASH_DIGITS_END, "")
        v = v.replace(Rx.UNDERSCORES, "_")
        v = v.replace(Rx.Q_DIGITS, "$1")
        v = v.replace(Rx.PAYTM, "")
        // "UPI/P2A/408812345678/NAME": keep the last segment that names someone.
        if ('/' in v) {
            v = v.split('/').map { it.trim() }.lastOrNull { seg ->
                seg.any(Char::isLetter) && !seg.matches(Rx.UPI_SEGMENT_WORD)
            } ?: ""
        }
        return v.trim('.', '-', '_', ' ')
    }

    // ------------------------------------------------------------------ j()

    private val dateFmt: String = run {
        val l = lists["CASENO17EXPDATE"]
        val day = l.getOrElse(0) { "" }.ifBlank { "3[01]|[12][0-9]|0?[1-9]" }
        val mon = l.getOrElse(1) { "" }.ifBlank { "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec" }
        "(($day)[-/ .]?(($mon)|0?[1-9]|1[012])[-/ .,]?([0-9]{2,4})?)"
    }
    private val J_ON_DATE = Regex("(?i)\\Won\\W$dateFmt\\Won\\W(.*?)(\\.|,|-|:)")
    private val J_P2P = Regex("\\s(P2P|P2A|P2M)\\W(.*?)(\\.|\\,|\\-)")
    private val J_TRF = Regex("(?i)\\(trf\\)(.*?)in\\syour")
    private val J_NEFT_FVG = Regex("(?i)\\Wneft\\sfvg\\sbenf(\\w+|)\\s(.*?)(\\.|\\,)")
    private val J_CREDITED_TO = Regex("(?i)Credited\\sto\\s(\\W|\\w){0,10}X{2,}([0-9]+)")
    private val UPI_REF = Regex("(?i)upi\\W(ref|referance|)(\\W+|)(no|number|)(\\W+|)([A-Z0-9]+\\W|)([0-9]{6,20})")
    private val UPI_REF_ALT = Regex("(?i)(ref(erence)?|rrn|utr)\\.?\\s*(no\\.?|num(ber)?|id)?\\s*[:#-]?\\s*([0-9]{9,20})")

    fun stepJ(s: ParseState) {
        // 1. P2P UPI to a mobile-number handle → Personal Contacts (interpretation of the "------" mode).
        if (s.vpa != null && Rx.MOBILE_PREFIX.containsMatchIn(s.vpa!!)) {
            if (s.paymentMode.isEmpty()) s.paymentMode = "UPI"
            s.category = TxnCategories.PERSONAL_CONTACTS
        }
        if (s.merchant.isEmpty()) {
            val w = s.w
            J_ON_DATE.find(w)?.let { m -> s.merchant = m.groupValues[m.groupValues.size - 2].trim(); s.rule += "j:ondate " }
            if (s.merchant.isEmpty()) J_P2P.find(w)?.let { s.merchant = it.groupValues[2].trim(); s.rule += "j:p2p " }
            if (s.merchant.isEmpty()) J_TRF.find(w)?.let { s.merchant = it.groupValues[1].trim(); s.rule += "j:trf " }
            if (s.merchant.isEmpty()) J_NEFT_FVG.find(w)?.let { s.merchant = it.groupValues[2].trim(); s.rule += "j:fvg " }
        }
        J_CREDITED_TO.find(s.text)?.let { s.toAccount = it.groupValues[2].takeLast(4) }
        if (s.merchant.isEmpty() && s.merchantPlaceholder.isNotEmpty()) {
            s.merchant = s.merchantPlaceholder.uppercase().let { if (it == "TXN") "" else it }
        }
        // UPI ref / netbanking (after j()).
        UPI_REF.find(s.text)?.let { s.upiRef = it.groupValues[6] }
        if (s.upiRef == null && s.paymentMode == "UPI") UPI_REF_ALT.find(s.text)?.let { s.upiRef = it.groupValues[5] }
        if (!s.sender.name.contains("SBI", true) && s.paymentMode.contains("Debited", true) && s.text.contains("INB")) {
            s.paymentMode = "netbanking"
        }
    }

    companion object {
        private val VPA_CONNECTORS = setOf("to", "from", "by", "on", "at", "via", "and", "a/c", "ac")
        private const val DEFAULT_UPI = "ybl|okaxis|okicici|oksbi|okhdfcbank|paytm|upi|ibl|axl|apl"
    }
}
