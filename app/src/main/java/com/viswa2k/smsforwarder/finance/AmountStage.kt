// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/AmountStage.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.finance.FinanceText.AMT_TERM
import com.viswa2k.smsforwarder.finance.FinanceText.P

/**
 * Stage H (`BankGetCreditDebitPart1`, `a.i()`, spec 05 §3.11): amount and credit/debit. The first
 * rule that yields an amount > 0 wins; later rules run only while the amount is 0. Runs on W.
 */
internal object AmountStage {

    private val ACCOUNT_WORD = Regex("(?i)(a/c|account|acc|acct|ac\\b)")
    private val R2_A = Regex(
        "(?i)(to|for)\\s*(\\W|\\w){0,10}(pay|transfer|debit|txn|trxn|tranx|trx|trans(\\w+|))\\s*(\\W|\\w){0,6}(\\W|^)(rs|inr)(\\W+|)[0-9].*?$AMT_TERM",
    )
    private val R2_B = Regex(
        "(?i)(to|for)\\s*(rs|inr)(\\W+|)([0-9,.]+)(\\W|\\w){0,10}(pay|transfer|debit|txn|trxn|tranx|trx|trans(\\w+|))\\W",
    )
    private val MAIN = Regex(
        "(?i)(\\W|^)((((txn|trxn|tranx|trx|trans|transaction|payment|pymnt|pment|pmnt|paymnt|transfer|tranc|pymt)(\\s|\\.)*of)|(transferred))\\s*(\\W|^)(Rs|INR)($P+|\\s*)\\s*[0-9].*?( (on|by|from|via|using|frm|to|at|for|in) |$))",
    )
    private val FUTURE = Regex("(?i)(will (\\w| |-){2,12}\\s|shall (\\w| |-){2,12}\\s)(debit|debited|deducted)")
    private val SCHEDULED = Regex("(?i)scheduled\\son")
    private val AMOUNT = Regex("(?i)(Rs|INR)($P+|\\s*)\\s*[0-9].*?$AMT_TERM")
    /** Credit-direction words; "credit card" names the instrument, not the direction (review fix). */
    private val CREDIT_WORDS = Regex("(?i)(credited|credit(?![\\s_-]*card)|deposite|deposited|receive|accept)")

    private val CHARGE = Regex("(?i)\\Wcharge\\s*of\\s*(\\W|^)(Rs|INR)($P+|\\s*)\\s*[0-9].*?initiated")
    private val RS_DEBIT_FROM = Regex(
        "(?i)((Rs|INR)($P+\\s*|\\s*)([0-9,\\.]+)((\\w|\\W){0,12})(debit|spent|debited|withdrawn|\\spaid|deducted|transferred)\\sfrom)",
    )
    private val SHALL_BE = Regex("(?i)(shall|will|would)\\s+be")
    private val RECEIVED_CREDIT_IN = Regex(
        "(?i)\\Wreceived(\\w|\\W){0,6}\\s*(credit in\\W)\\s*(\\W|\\w){0,8}(xx|\\.\\.|\\*\\*)+[0-9]{2,8}.*?((\\W|^)(Rs|INR)($P+|\\s*)\\s*[0-9].*?$AMT_TERM)",
    )
    private val CREDIT_WITH = Regex("(?i)\\Wcredit with(\\W|\\w){1,10}(Rs|INR)\\W+([0-9,]+\\.\\d+|[0-9,]+)")
    private val INITIATED_FROM = Regex("(?i)\\W(Rs|INR)\\W+([0-9,]+\\.\\d+|[0-9,]+)\\Winitiated\\sfrom(\\W|\\w){1,35}on\\W")
    private val EMI_ACTIVATION = Regex("(?i)for (using|paying)\\semi")
    private val TXN_SUCCESS = Regex(
        "(?i)(txn|trxn|tranx|trx|trans|transaction).*?(\\W|^)(Rs|INR)(\\W+|)\\s*([0-9].*?)( |[a-z]).*?( successful| approved)",
    )
    private val CARD_RECEIVED = Regex("(?i)(\\W|^)(Rs|INR)(\\W+|)([0-9,\\.]+)\\sreceive((\\W|\\w){0,30})")
    private val RECEIVED_IN_YOUR = Regex("(?i)received\\s(Rs|INR)(\\W+|)([0-9,.]+)\\sin\\syour\\s((\\w|\\W){3,10})\\sx{1,}[0-9]{3,}")
    private val HDFC_TXN = Regex("^(?i)\\s*(txn|trxn|tranx|trx|trans|transaction|tranc)\\W+(Rs|INR)(\\W+|)([0-9\\,\\.]+)\\W")
    private val SENT = Regex("^(?i)\\s*(money\\s*|)(sent)\\W+(Rs|INR)(\\W+|)([0-9\\,\\.]+)\\W")
    private val LOADED = Regex("(?i)(\\W|^)(Rs|INR)(\\W+|)([0-9,.]+)\\sloaded")
    private val LOADED_WITH = Regex("(?i)\\Wloaded\\swith\\W(Rs|INR)(\\W+|)([0-9,.]+)")

    private val POSTED = Regex("(?i)has\\sbeen\\sposted")
    private val FB_CREDIT = Regex("(?i)(credited|credit of|deposite|deposited)")
    private val FB_DEBIT = Regex("(?i)(debit|spent|purchase|withdrawn|withdrawal|\\sdr\\b|\\spaid|charged|deducted)")
    private val RS_NUMBER = Regex("(?i)(Rs|INR)\\s*($P+|\\s*)\\s*([0-9][0-9,]*(\\.[0-9]+)?)")

    private val SBI_ITR = Regex("(?i)(\\Wit\\W|ITD|Tax)\\s*refund|itr\\s*register")
    private val WITHDRAW = Regex("(?i)withdrawn|withdrawal")

    fun run(s: ParseState) {
        val w = s.w
        val l = w.lowercase()

        // 1. Self-transfer between own accounts.
        if (!s.hasCard && "debited" in l && "credited" in l) {
            val from = s.ownAccountDigits ?: s.otherAccount1
            val to = s.secondOwnDigits ?: if (s.ownAccountDigits != null) s.otherAccount1 else s.otherAccount2
            // Interpretation: both sides must read as "your <account>", otherwise "debited from your a/c
            // and credited to <VPA>" (a payment) would be dropped.
            if ((from == null || to == null || from == to) && ownAccountNear(s.text, "debited") && ownAccountNear(s.text, "credited")) {
                s.status = ParseStatus.NOT_TXN
                s.rule += "H1 self "
                return
            }
        }
        // 2. Non-bank (wallet / merchant) senders: "to pay Rs X" (interpretation of the original's sender class 4).
        if (!s.sender.isBank) {
            R2_A.find(w)?.let { m -> take(s, amountIn(m.value), TxnDirection.DEBIT, "H2a") }
            if (s.amount == 0.0) R2_B.find(w)?.let { m -> take(s, FinanceText.cleanNumber(m.groupValues[4]), TxnDirection.DEBIT, "H2b") }
        }
        // 3. Main pattern.
        if (s.amount == 0.0) for (m in MAIN.findAll(w)) {
            val ctx = FinanceText.before(w, m.range.first, 25) + m.value
            if (FUTURE.containsMatchIn(ctx) || "convert" in ctx.lowercase() || "authorize" in ctx.lowercase() || SCHEDULED.containsMatchIn(ctx)) continue
            val amount = amountIn(m.value)
            if (amount <= 0.0) continue
            val around = FinanceText.before(w, m.range.first, 15) + m.value + FinanceText.after(w, m.range.last + 1, 15)
            val credit = CREDIT_WORDS.containsMatchIn(around) && !around.contains("towards", ignoreCase = true)
            if (credit && s.cardType == CardTypes.DEBIT) s.cardType = CardTypes.CREDIT
            take(s, amount, if (credit) TxnDirection.CREDIT else TxnDirection.DEBIT, "H3")
            break
        }
        // 4. Further patterns.
        if (s.amount == 0.0) CHARGE.find(w)?.let { take(s, amountIn(it.value), TxnDirection.DEBIT, "H4charge") }
        if (s.amount == 0.0) for (m in RS_DEBIT_FROM.findAll(w)) {
            if (SHALL_BE.containsMatchIn(FinanceText.after(w, m.range.last + 1, 15)) || SHALL_BE.containsMatchIn(m.value)) continue
            // Review fix: "Rs X debited from a/c of <payer> and credited to your a/c" is the payee's credit.
            val dir = if (CREDITED_TO_YOUR.containsMatchIn(w) && !YOUR_FOLLOWS.containsMatchIn(FinanceText.after(w, m.range.last + 1, 20).lowercase())) {
                TxnDirection.CREDIT
            } else TxnDirection.DEBIT
            take(s, FinanceText.cleanNumber(m.groupValues[4]), dir, "H4debitfrom")
            break
        }
        if (s.amount == 0.0) RECEIVED_CREDIT_IN.find(w)?.let { take(s, amountIn(it.groupValues[5]), TxnDirection.CREDIT, "H4recvcredit") }
        if (s.amount == 0.0) CREDIT_WITH.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[3]), TxnDirection.CREDIT, "H4creditwith") }
        if (s.amount == 0.0) INITIATED_FROM.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[2]), TxnDirection.DEBIT, "H4initiated") }
        if (s.amount == 0.0 && EMI_ACTIVATION.containsMatchIn(w) && "activ" in l) {
            s.status = ParseStatus.NOT_TXN
            s.rule += "H4emiactivation "
            return
        }
        if (s.amount == 0.0) TXN_SUCCESS.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[5]), TxnDirection.DEBIT, "H4success") }
        if (s.amount == 0.0 && s.isCreditCardContext()) CARD_RECEIVED.find(w)?.let { m ->
            take(s, FinanceText.cleanNumber(m.groupValues[4]), TxnDirection.CREDIT, "H4cardrecv")
            s.kind = if (" for" in m.groupValues[5].lowercase()) TxnKind.REFUND else TxnKind.CC_PAYMENT_RECEIVED
        }
        if (s.amount == 0.0) RECEIVED_IN_YOUR.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[3]), TxnDirection.CREDIT, "H4recvyour") }
        if (s.amount == 0.0 && s.sender.name.contains("HDFC", true)) HDFC_TXN.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[4]), TxnDirection.DEBIT, "H4hdfc") }
        if (s.amount == 0.0) SENT.find(w)?.let { take(s, FinanceText.cleanNumber(it.groupValues[5]), TxnDirection.DEBIT, "H4sent") }
        if (s.amount == 0.0 && ("pre paid" in l || "prepaid" in l || "wallet" in l || s.subType == "wallet")) {
            (LOADED.find(w)?.groupValues?.get(4) ?: LOADED_WITH.find(w)?.groupValues?.get(3))?.let {
                take(s, FinanceText.cleanNumber(it), TxnDirection.CREDIT, "H4loaded")
                s.kind = TxnKind.WALLET_LOAD
            }
        }
        // 5. Fallback: nearest Rs number to the credit / debit keyword.
        // Interpretation: a future / scheduled debit ("will be debited") never reaches the fallback either.
        if (s.amount == 0.0 && !FUTURE.containsMatchIn(w) && !SCHEDULED.containsMatchIn(w)) {
            val t = POSTED.replace(w, " ")
            val credit = FB_CREDIT.find(t)
            val debit = FB_DEBIT.find(t)
            val (kw, dir) = when {
                credit != null && debit != null -> pickFallbackKeyword(t, credit, debit)
                credit != null -> credit to TxnDirection.CREDIT
                debit != null -> debit to TxnDirection.DEBIT
                else -> null to TxnDirection.NONE
            }
            if (kw != null) {
                val nearest = RS_NUMBER.findAll(t).minByOrNull { n ->
                    val c = kw.range.first
                    if (n.range.last < c) c - n.range.last else n.range.first - kw.range.last
                }
                nearest?.let { take(s, FinanceText.cleanNumber(it.groupValues[3]), dir, if (dir == TxnDirection.CREDIT) "H5credit" else "H5debit") }
            }
        }
        // 6. Adjustments.
        if (s.direction == TxnDirection.CREDIT && s.sender.name.contains("SBI", true) &&
            Rx.PAN.containsMatchIn(s.text) && SBI_ITR.containsMatchIn(s.text) && Rx.AY.containsMatchIn(s.text)
        ) {
            s.status = ParseStatus.NOT_TXN
            s.rule += "H6itr "
            return
        }
        if (WITHDRAW.containsMatchIn(w) && s.amount > 24000) s.paymentMode = "CASH"
        if (s.paymentMode.isEmpty() && s.direction != TxnDirection.NONE) {
            s.paymentMode = if (s.direction == TxnDirection.DEBIT) "Debited" else "Credited"
        }
    }

    /**
     * Review fix (fallback with both a credit and a debit keyword): the original took "credited" first,
     * so "a/c XX123 debited for Rs 500; AMAZON credited" became a credit. The keyword bound to the
     * user's own account ("your" just before it, or "to/in your" just after it) wins; otherwise the
     * earlier keyword wins, so "<payee> credited" / "credited to <x>" after a debit is the payee side.
     */
    private fun pickFallbackKeyword(t: String, credit: MatchResult, debit: MatchResult): Pair<MatchResult, TxnDirection> {
        fun bound(m: MatchResult): Boolean =
            "your" in FinanceText.before(t, m.range.first, 25).lowercase() ||
                OWN_AFTER.containsMatchIn(FinanceText.after(t, m.range.last + 1, 20))
        val c = bound(credit)
        val d = bound(debit)
        return when {
            c && !d -> credit to TxnDirection.CREDIT
            d && !c -> debit to TxnDirection.DEBIT
            credit.range.first < debit.range.first -> credit to TxnDirection.CREDIT
            else -> debit to TxnDirection.DEBIT
        }
    }

    private val OWN_AFTER = Regex("(?i)^\\W*(to|in|into)\\s+your\\b")

    /**
     * The keyword reads as the user's own account: "your" + an account word in the 40 chars before it,
     * or "from / to / in your" right after it (review fix: "debited from a/c of X and credited to your
     * a/c" is a credit, not a self-transfer).
     */
    private fun ownAccountNear(text: String, keyword: String): Boolean {
        val l = text.lowercase()
        val i = l.indexOf(keyword)
        if (i < 0) return false
        val before = l.substring((i - 40).coerceAtLeast(0), i)
        if (OWN_BEFORE.containsMatchIn(before)) return true
        return OWN_FOLLOWS.containsMatchIn(l.substring(i + keyword.length, (i + keyword.length + 20).coerceAtMost(l.length)))
    }

    private val YOUR_FOLLOWS = Regex("^\\W*((from|to|in|into)\\s+)?your\\b")
    private val CREDITED_TO_YOUR = Regex("(?i)credited\\s+(to|in|into)\\s+your\\b")
    /** "your <a/c> [XX1234] [is / has been]" right before the keyword. */
    private val OWN_BEFORE = Regex("your\\s+(\\w+\\s+){0,2}(a/c|account|acc|acct|ac)\\b[\\W\\d]*(xx\\d+[\\W]*)?((is|has|been|was|got|being|now)\\s+){0,3}$")
    private val OWN_FOLLOWS = Regex("^\\W*(from|to|in|into)\\s+your\\s+(\\w+\\s+)?(a/c|account|acc|acct|ac)\\b")

    private fun ParseState.isCreditCardContext(): Boolean =
        cardType == CardTypes.CREDIT || accountType == CardTypes.CREDIT || text.contains("credit card", ignoreCase = true)

    /** First Rs/INR number in [match], with the clean-up rule. */
    fun amountIn(match: String): Double {
        val m = AMOUNT.find(match) ?: return 0.0
        return FinanceText.cleanNumber(m.value.replace(Rx.CURRENCY_PREFIX, ""))
    }

    private fun take(s: ParseState, amount: Double, direction: TxnDirection, rule: String) {
        if (amount <= 0.0) return
        s.amount = amount
        s.direction = direction
        s.rule += "$rule "
    }
}
