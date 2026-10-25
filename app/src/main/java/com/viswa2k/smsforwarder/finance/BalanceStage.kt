// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/BalanceStage.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.finance.FinanceText.P
import com.viswa2k.smsforwarder.finance.FinanceText.TERM

/**
 * Stage E (`BankGetBalanceDetails`, `a.d()`, spec 05 §3.8): available balance, credit-card available
 * limit and outstanding.
 */
internal object BalanceStage {

    private val DEBIT_BY = Regex("(?i)(debit(|ed)\\s(by|of|with))(\\W+)(\\d+\\.\\d+|\\d+)(\\W)")
    private val UPI_THRU = Regex("(?i)\\b(upi|thru)\\b")
    private val GATE = Regex("(?i)(bal|balance|amt|amount)")

    private val PRIMARY = Regex("(?i)(bal|balance|amt|amount)\\s*($P+|\\s*|is)\\s*(\\W){0,2}[0-9].*?$TERM")
    private val PRIMARY_NUMBER = Regex("(?i)($P+|\\s*)(\\W){0,2}[0-9].*?$TERM")
    private val AVAIL = Regex("(?i)(avl|available|avlbl|avail)")

    private val FB_A = Regex("(?i)(bal|balance|amt|amount)(\\W+|\\s*)is\\s*(\\W){0,2}(\\d+\\.\\d+)")
    private val FB_B = Regex("(?i)(Rs|INR)\\s*[\\W]{0,3}([0-9,.]+)\\sis\\s(\\W|\\w){0,15}(bal\\W|balance)")
    private val FB_C = Regex("(?i)((^|\\W)(avl\\s*|avlbl\\s*)?bal|balance)\\W.*?(Rs|INR)\\s*(?<sep>[\\W_]+|\\s*)\\s*(?<num>[0-9].*?)$TERM")
    private val FB_C_LIMIT = Regex("(?i)limit.*?(Rs|INR)\\W[0-9]")
    private val FB_D = Regex("(?i)(bal|balance|amt|amount)\\s*in.*?is\\s*(Rs|INR)\\s*[\\W]{0,3}(?<num>[0-9].*?)$TERM")

    private val LIMIT = Regex(
        "(?i)\\W(limit|lmt)(\\W)\\s*(\\W|\\w){0,10}?(is|\\W+)\\s*(\\W|\\w){0,10}?(?<cur>Rs|INR|)($P+|\\s*)(\\W){0,3}(?<num>[0-9].*?)($TERM|\\n| )",
    )
    /**
     * Rebuild addition: the spec pattern's `(\W|\w){0,10}…(is|\W+)…(Rs|INR|)` lets the optional currency
     * group stay empty whenever the engine can absorb "Rs" in the wildcard, so "Avl Lmt: INR 88,501.00"
     * fails the "Rs/INR present" check. This direct form is tried first.
     */
    private val LIMIT_DIRECT = Regex("(?i)\\W(limit|lmt)\\W*(is\\W*|)(of\\W*|)(?<cur>Rs|INR)\\s*(?<num>[0-9][0-9,]*(\\.[0-9]+)?)")

    private val OUTSTANDING = Regex(
        "(?i)((outstanding|o\\/s| os\\s)\\s*(\\W|\\w){0,10}(Rs|INR)($P+|\\s*)(\\W){0,3}(?<num>[0-9].*?)$TERM)",
    )

    private val DECIMAL = Regex("\\d\\.\\d")

    private data class Candidate(val match: String, val value: Double, val index: Int, val decimals: Boolean)

    fun run(s: ParseState) {
        var t = DEBIT_BY.replace(s.text, "$1$4inr $5$6")
        t = UPI_THRU.replace(t, " ")
        if (GATE.containsMatchIn(t)) {
            balanceCandidates(s, t)
            if (s.balance == null) fallbacks(s, t)
        }
        limit(s)
        if (s.balance != null) outstanding(s)
    }

    private fun balanceCandidates(s: ParseState, t: String) {
        val candidates = ArrayList<Candidate>()
        for (m in PRIMARY.findAll(t)) {
            if ('@' in m.value) continue
            if (!balanceWorded(t, m)) continue
            val numPart = m.value.substring(m.groups[1]!!.value.length)
            val n = PRIMARY_NUMBER.find(numPart)?.value ?: continue
            val v = FinanceText.cleanNumber(n)
            if (v <= 0.0 && !n.contains('0')) continue
            candidates += Candidate(m.value, v, m.range.first, DECIMAL.containsMatchIn(n))
        }
        if (candidates.isEmpty()) return
        val best = if (candidates.size == 1) candidates[0] else candidates.maxBy { c -> score(c, candidates, t) }
        s.balance = best.value
        s.rule += "E1 "
    }

    /**
     * Review fix: an "amt / amount" keyword is a balance only with a balance word ("avl", "available",
     * "bal") in the 15 chars before it; a bare "Amount: 500.00" is the transaction amount.
     */
    private fun balanceWorded(t: String, m: MatchResult): Boolean {
        val kw = m.groupValues[1].lowercase()
        if (kw.startsWith("bal")) return true
        return AMT_BALANCE_CONTEXT.containsMatchIn(FinanceText.before(t, m.range.first, 15))
    }

    private val AMT_BALANCE_CONTEXT = Regex("(?i)(avl|available|avlbl|avail|bal)")

    /** Pairwise scoring of balance candidates (spec 05 §3.8 step 2). */
    private fun score(c: Candidate, all: List<Candidate>, t: String): Int {
        var score = 0
        if (AVAIL.containsMatchIn(FinanceText.before(t, c.index, 15) + c.match.take(8))) score += 50
        for (o in all) {
            if (o === c) continue
            if (c.match.length > o.match.length) score += 1
            if (c.value > o.value) score += 1
            // Interpretation: "+1 to the other candidate when one has decimals" read as: the candidate
            // with decimals wins the tie against one without (a balance is usually printed with paise).
            if (c.decimals && !o.decimals) score += 1
        }
        if (maskedTail(c)) score -= 1
        return score
    }

    private fun maskedTail(c: Candidate): Boolean {
        val digits = Rx.DIGIT_RUN.findAll(c.match).joinToString("") { it.value }
        val tail = digits.takeLast(if (c.decimals) 6 else 3)
        return tail.length >= 3 && tail.all { it == tail[0] }
    }

    private fun fallbacks(s: ParseState, t: String) {
        FB_A.findAll(t).firstOrNull { balanceWorded(t, it) }?.let { s.balance = it.groupValues[4].toDoubleOrNull(); s.rule += "Ea " }
        if (s.balance != null) return
        FB_B.find(t)?.let { s.balance = FinanceText.cleanNumber(it.groupValues[2].replace(",", "")); s.rule += "Eb " }
        if (s.balance != null) return
        for (m in FB_C.findAll(t)) {
            val v = m.value.trimStart()
            if (v.lowercase().trimStart { !it.isLetter() }.startsWith("balance transfer")) {
                s.accountType = CardTypes.CREDIT
                return
            }
            if (FB_C_LIMIT.containsMatchIn(v)) continue
            val n = FinanceText.cleanNumber(m.groups["num"]!!.value)
            val negative = m.groups["sep"]!!.value.contains('-')
            s.balance = if (negative) -n else n
            s.rule += "Ec "
            return
        }
        FB_D.findAll(t).firstOrNull { balanceWorded(t, it) }?.let { s.balance = FinanceText.cleanNumber(it.groups["num"]!!.value); s.rule += "Ed " }
    }

    /** Step 4: credit-card available limit, on W. */
    private fun limit(s: ParseState) {
        val m = LIMIT_DIRECT.find(s.w) ?: LIMIT.find(s.w) ?: return
        var num = m.groups["num"]!!.value.trimEnd('.', ',')
        val hasCurrency = m.groups["cur"]!!.value.isNotEmpty()
        val sender = (s.sender.name + " " + s.sender.id).lowercase()
        val padAllowed = "kotak" in sender || "axis" in sender || "unity" in sender
        val hasDecimal = DECIMAL.containsMatchIn(num)
        if (!hasDecimal && padAllowed) num += ".00"
        val ok = (DECIMAL.containsMatchIn(num) && hasCurrency) || "axis" in sender
        if (!ok) return
        val v = FinanceText.cleanNumber(num)
        if (v <= 0.0) return
        s.balance = v
        s.accountType = CardTypes.CREDIT
        s.rule += "E4 "
    }

    /** Step 5: outstanding (only once a limit or balance was found). */
    private fun outstanding(s: ParseState) {
        val m = OUTSTANDING.find(s.text) ?: return
        val v = FinanceText.cleanNumber(m.groups["num"]!!.value)
        if (v <= 0.0) return
        s.outstanding = v
        s.accountType = CardTypes.CREDIT
        s.rule += "E5 "
    }
}
