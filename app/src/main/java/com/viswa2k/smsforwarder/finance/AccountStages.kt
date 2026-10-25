// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/AccountStages.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.finance.FinanceText.ACC
import com.viswa2k.smsforwarder.finance.FinanceText.P

/**
 * Stages A–D of the transaction parser (spec 05 §3.4–3.7): account number, card number / type,
 * remaining account forms and "not sure" masked digits. Regexes are the spec's, with `ACC` expanded,
 * `[[:punct:]]` as `\p{Punct}` and Kotlin escaping.
 */
internal object AccountStages {

    // ------------------------------------------------------------------ Stage A

    private val A_P1 = Regex(
        "(?i)$ACC(\\s|$P+)(no|$P+|n|)(\\W+|)($P+|ending|ends|end|)\\s*($P+|with|)($P+|in|)" +
            "(((\\w|\\W){0,12}((\\.|\\*){2,}|(x){1,}))|\\s*)\\s*[0-9\\/]{2,}",
    )
    private val A_P1_MASK = Regex("(?i)((\\.|\\*|){2,}|(x){1,})\\s*[0-9\\/]{2,}")

    private val A_P2 = Regex(
        "(?i)$ACC(\\s|$P+)(no|$P+|n|)\\s*($P+|)\\s*(\\W|\\w){0,20}((\\.|\\*){2,}|(x){1,})\\s*[0-9\\/]{2,}",
    )
    private val A_P2_MASK = Regex("(?i)((\\.|\\*){2,}|(x){1,})\\s*[0-9]{2,}")

    private val A_P3 = Regex("(?i)$ACC((\\.|\\*){2,}|(x){1,}|)\\s*(?<digits>[0-9\\/]{4,})( |\\.|\\:)")

    private val FD = Regex("(?i)(^|\\W)(Deposit|FD|RD)(\\W|$)")

    /** Sub-type check (`c()`): the 10 chars before the matched phrase. */
    fun subTypeBefore(text: String, index: Int): String? {
        val ctx = FinanceText.before(text, index, 10).lowercase()
        return when {
            "loan" in ctx || "emi" in ctx -> "loan"
            "wallet" in ctx -> "wallet"
            "fastag" in ctx -> "fastag"
            FD.containsMatchIn(ctx) -> "Fixed Deposit"
            else -> null
        }
    }

    /** Stage A (`AccountDetails`, `a.b()`). */
    fun stageA(s: ParseState) {
        val snapshot = s.w
        var found = false
        for (m in A_P1.findAll(snapshot)) {
            if (takeAccountToken(s, snapshot, m, A_P1_MASK)) found = true
        }
        if (!s.ownFound) {
            for (m in A_P2.findAll(s.w)) {
                if (takeAccountToken(s, s.w, m, A_P2_MASK)) found = true
            }
        }
        if (!found && s.accountToken1 == null) {
            A_P3.find(s.w)?.let { m ->
                val digits = m.groups["digits"]!!.value.replace("/", "")
                if (digits.length >= 4) {
                    s.accountLast4 = digits.takeLast(4)
                    s.ownAccountDigits = digits
                    s.accountToken1 = m.groups["digits"]!!.value
                    s.subType = subTypeBefore(s.w, m.range.first) ?: s.subType
                    s.consume(m.groups["digits"]!!.value)
                    s.rule += "A3 "
                }
            }
        }
        if (s.accountLast4 == null && s.otherAccount1 != null) s.accountLast4 = s.otherAccount1
    }

    /**
     * Records one masked account token of a P1/P2/C match (spec 05 §3.4 step 1): the word containing
     * the mask; "your" in the 30 chars before it = own account. Returns true when a token was taken.
     */
    private fun takeAccountToken(s: ParseState, text: String, m: MatchResult, mask: Regex, rejectInnerDot: Boolean = false): Boolean {
        val inner = mask.find(m.value) ?: return false
        val maskStart = m.range.first + inner.range.first
        val wordRange = FinanceText.wordAround(text, maskStart, maskStart + inner.value.length)
        val token = text.substring(wordRange.first, wordRange.last + 1)
        if (rejectInnerDot && token.drop(1).trimEnd('.').contains('.')) return false
        val digits = FinanceText.lastDigits(token) ?: return false
        val own = "your" in FinanceText.before(text, wordRange.first, 30).lowercase()
        if (own && !s.ownFound) {
            s.accountLast4 = digits
            s.ownAccountDigits = digits
            s.ownFound = true
            s.accountToken1 = token
            s.subType = subTypeBefore(text, m.range.first) ?: s.subType
        } else if (own) {
            if (digits != s.ownAccountDigits || s.secondOwnDigits == null) s.secondOwnDigits = digits
        } else {
            when {
                s.otherAccount1 == null -> {
                    s.otherAccount1 = digits
                    if (s.accountToken1 == null) s.accountToken1 = token
                    if (s.subType == null) s.subType = subTypeBefore(text, m.range.first)
                }
                s.otherAccount2 == null && digits != s.otherAccount1 -> {
                    s.otherAccount2 = digits
                    s.accountToken2 = token
                }
            }
        }
        s.consume(token)
        s.rule += "A "
        return true
    }

    // ------------------------------------------------------------------ Stage B

    private const val T = "(debit|credit|Meal|Prepaid|)(\\s*|$P+)(card)"
    private val CARD_BRANCHES = listOf(
        "C1" to Regex("(?i)$T\\s*(no|n|number)\\s*($P+|ending|ends|end)\\s*($P+|with|)\\s*(\\W|\\w){0,20}((\\.|\\*){2,}|(x){1,})\\s*[0-9]{2,}"),
        "C2" to Regex("(?i)$T\\s*(no|n|number)\\s*($P+|ending|ends|end)\\s*($P+|with|)\\s*[a-zA-z]{0,5}\\s*((\\.|\\*|){2,}|(x){1,})\\s*[0-9]{2,}"),
        "C3" to Regex("(?i)$T\\s*(|$P+|ending|ends|end)\\s*(\\W|\\w){0,20}((\\.|\\*){2,}|(x){1,})\\s*[0-9]{2,}"),
        "C4" to Regex("(?i)(debit|credit|Meal|Prepaid)(\\s*|$P+)(card)\\s*(no|n|number|)\\s*(\\W+|ending|ends|end|)\\s*($P+|with|)\\s*(?<digits>[0-9]{2,})"),
        "C5" to Regex("(?i)(card)\\s*(?<digits>[0-9]{4,})( |,|\\.|\\:)"),
        "C6" to Regex("(?i)$T\\s*($P+|ending|ends|end)\\s*($P+|with|)\\s*[a-zA-z]{0,5}\\s*((\\.|\\*|){2,}|(x){1,})\\s*[0-9]{2,}"),
    )
    private val CARD_CLEAN = Regex("[a-wA-Wy-zY-Z]")
    private val CARD_IS_ACCOUNT = Regex("(?i)(debit|credit|Meal|Prepaid)\\scard\\s(a\\/c|account|acc|acct|accnt|ac)")
    private val CARD_WORDS = Regex("(?i)(credit|debit)\\s*card")

    /** Card type (`f()`), from the matched phrase. */
    fun cardType(phrase: String): String {
        val p = phrase.lowercase()
        return when {
            "meal" in p -> CardTypes.MEAL
            "prepaid" in p -> CardTypes.PREPAID
            "credit" in p && "debit" !in p -> CardTypes.CREDIT
            else -> CardTypes.DEBIT
        }
    }

    /** Stage B (`CardDetails`, `a.h()`). */
    fun stageB(s: ParseState) {
        if ("card" !in s.lower) return
        for ((name, re) in CARD_BRANCHES) {
            val m = re.find(s.w) ?: continue
            val digitsGroup = if (name == "C4" || name == "C5") m.groups["digits"]?.value else null
            val token = digitsGroup ?: CARD_CLEAN.replace(m.value, "")
            val last = FinanceText.lastDigits(digitsGroup ?: token) ?: continue
            if (name == "C4" && last.length < 2) continue
            s.cardLast4 = last
            // A card phrase also matched by Stage A ("credit card a/c XX1234") was really the card.
            if (s.accountToken1 != null && s.accountToken1!!.contains(last) && CARD_IS_ACCOUNT.containsMatchIn(m.value)) {
                s.ownAccountDigits = null
                s.ownFound = false
            }
            s.cardType = cardType(m.value)
            s.w = s.w.replaceRange(m.range, " ")
            s.w = CARD_WORDS.replace(s.w, " ")
            s.accountLast4 = "-1"
            s.rule += "B:$name "
            return
        }
    }

    // ------------------------------------------------------------------ Stage C

    private val C_REMAINING = Regex(
        "(?i)($ACC(\\s|$P+)(no|$P+|n|[^a-z])\\s*($P+|)\\s*[a-zA-z]{0,5}\\s*((\\.|\\*|){2,}|(x|-|\\/){1,})\\s*[0-9]{2,}( |\\. |\\.\\w|.*?([0-9]+( |\\.|[a-zA-z])))" +
            "|($ACC(\\s|$P+)(no|$P+|n|)\\s*($P+|ending|ends|end)\\s*($P+|with|)\\s*[a-zA-z]{0,5}\\s*((\\.|\\*|){2,}|(x|-|\\/){1,})\\s*[0-9]{2,}))",
    )
    private val C_MASK = Regex("(?i)((\\.|\\*|){2,}|(x|-|\\/){1,})\\s*[0-9]{2,}")

    /** Stage C (`GetRemainingACDetails`): only when no account yet and W contains "ac". */
    fun stageC(s: ParseState) {
        if (s.hasAccount || s.hasCard || "ac" !in s.lower) return
        val snapshot = s.w
        for (m in C_REMAINING.findAll(snapshot)) {
            if (m.value.length > 50) continue
            takeAccountToken(s, snapshot, m, C_MASK, rejectInnerDot = true)
        }
        if (s.accountLast4 == null && s.otherAccount1 != null) s.accountLast4 = s.otherAccount1
    }

    // ------------------------------------------------------------------ Stage D

    private val D_MASKED = Regex("(?i)(x|\\.|\\*){2,}\\s*[0-9]{2,}\\W")
    private val D_REJECT = Regex(
        "(?i)(reference|\\Wref\\W|\\Wutr\\W|\\Wfolio|\\Wpan\\W|aadhar|dmat|\\WID\\W|application|beneficiary|\\Wbenf|UMN)",
    )
    private val D_SB = Regex("(?i)\\Wsb\\W")

    /** Stage D (`BankGetNotSureCases`). */
    fun stageD(s: ParseState) {
        val l = s.lower
        if (s.hasAccount || s.hasCard || "beneficiary" in l) return
        if ("xx" !in l && ".." !in l && "**" !in l) return
        for (m in D_MASKED.findAll(s.w)) {
            if (D_REJECT.containsMatchIn(FinanceText.before(s.w, m.range.first, 15))) continue
            val digits = FinanceText.lastDigits(m.value) ?: continue
            s.accountLast4 = digits
            s.accountUncertain = !D_SB.containsMatchIn(FinanceText.before(s.w, m.range.first, 5))
            val sub = subTypeBefore(s.w, m.range.first)
            if (sub != null && sub != "wallet" && sub != "fastag") s.subType = sub
            s.consume(m.value.trimEnd())
            s.rule += "D "
            return
        }
    }
}
