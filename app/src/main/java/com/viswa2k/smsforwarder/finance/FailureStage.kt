// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/FailureStage.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

/**
 * Stage J (`a.e()`, spec 05 §3.13): failures, reversals, refunds and reminder look-alikes. The rebuild
 * runs it for every message (original only without an account/card). Rules 1–3 and 5–10 run before Stage
 * H ([pre]) so a refund amount wins and a reminder is dropped early; rule 4 ("has been reversed")
 * flips the direction Stage H found, so it runs after H ([post]).
 */
internal object FailureStage {

    private val FAILED = Regex(
        "(?i)(bounced|returned|dishonnered|dishonored|dishonoured|not proceed|declined|timed out|rejected|failed|wrong\\spin\\sentered)",
    )
    private val REVERSAL_DONE = Regex(
        "(?i)(credited\\s+back|has\\s+been\\s+(reversed|refunded)|transaction\\s+reversed|\\brefunded\\s+(to|in|into)\\b|reversal\\s+of)",
    )
    private val FUTURE_REFUND = Regex("(?i)(will|shall|would)\\s+be\\s+(reversed|refunded|credited)")
    private val WITHHELD = Regex("(?i)is\\swithheld")
    private val CONFIRM = Regex(
        "(?i)confirm\\sfollowing\\s(txn|trxn|tranx|trx|trans|transaction|payment|pymnt|pment|pmnt|paymnt|transfer|tranc|pymt)",
    )
    private val REVERSED = Regex("(?i)has\\sbeen\\sreversed")
    private val REFUNDED = Regex("(?i)(rs|inr)(\\W+)([0-9,\\.]+)\\srefunded")
    private val REFUND_PAYEE = Regex("(?i)\\W(by|from)\\W+((\\w+)\\W+(\\w+)\\W+(\\w+))\\W+")
    private val TXN_REVERSED = Regex("(?i)transaction\\sreversed")
    private val TXN_REVERSED_DATE = Regex("(?i)\\son\\s[0-9]{2}")
    private val TXN_REVERSED_AMOUNT = Regex("(?i)(Rs|INR)(\\W+|)([0-9,\\.]+)\\s")
    private val NEGATED = Regex(
        "(?i)(not|dis|isn('|)t|wasn('|)t|hasn('|)t|couldn('|)t|could not be)\\s*(-|)\\s*(been|)\\s*(approved|authorized|successful)",
    )
    private val BILL_WORDS = Regex("(?i)(ignore|bill|emi|card|loan|installment|instlmnt|recharge|paylater)")
    private val DUE = Regex("(?i)(due)[\\W]{1,3}(date|dt|rs|inr|on|by|for|is(\\W)|are(\\W)|was(\\W)|were|today|tommorrow)")
    private val IF_PAID = Regex("(?i)(\\W|^)(if\\s*(\\w|\\W){0,25}\\spaid)")
    private val DUE_BY = Regex("(?i)due\\s(of|\\W+|)\\s*(rs|inr)(\\W+|)(\\d+\\.\\d+|\\d+)\\W+by")
    private val AUTOPAY = Regex("(?i)auto(\\W+|)pay(\\w+|)\\W(reminder|activation)")
    private val AUTOPAY2 = Regex("(?i)reminder\\W+upcoming\\W+auto(\\W+|)pay(\\w+|)")

    /** Rules 1–3 and 5–10. Returns false when the message is not a transaction (status 0). */
    fun pre(s: ParseState): Boolean {
        val t = s.text
        // Review fix: a completed reversal / refund ("credited back … for failed UPI txn") is a refund
        // credit, not a failure.
        val reversalDone = REVERSAL_DONE.containsMatchIn(t) && !FUTURE_REFUND.containsMatchIn(t)
        if (reversalDone) {
            s.rule += "J0 reversal done "
        } else if (FAILED.containsMatchIn(t)) {
            s.status = ParseStatus.FAILED
            s.cancelsMatchingDebit = true
            s.rule += "J1 failed "
        } else if (WITHHELD.containsMatchIn(t)) {
            s.status = ParseStatus.FAILED
            s.rule += "J2 withheld "
        } else if (CONFIRM.containsMatchIn(t)) {
            s.status = ParseStatus.FAILED
            s.rule += "J3 confirm "
        }
        REFUNDED.find(t)?.let { m ->
            val v = FinanceText.cleanNumber(m.groupValues[3])
            if (v > 0.0) {
                s.amount = v
                s.direction = TxnDirection.CREDIT
                s.kind = TxnKind.REFUND
                REFUND_PAYEE.find(t, m.range.last)?.let { p -> if (s.merchant.isEmpty()) s.merchant = p.groupValues[2] }
                s.rule += "J5 refund "
            }
        }
        if (s.amount == 0.0 && TXN_REVERSED.containsMatchIn(t) && !TXN_REVERSED_DATE.containsMatchIn(t)) {
            TXN_REVERSED_AMOUNT.find(t)?.let { m ->
                val v = FinanceText.cleanNumber(m.groupValues[3])
                if (v > 0.0) {
                    s.amount = v
                    s.direction = TxnDirection.CREDIT
                    s.kind = TxnKind.REFUND
                    s.rule += "J6 reversed "
                }
            }
        }
        if (s.status == null && NEGATED.containsMatchIn(t)) {
            if (!BILL_WORDS.containsMatchIn(t)) {
                s.status = ParseStatus.FAILED
                s.cancelsMatchingDebit = true
                s.rule += "J7 negated "
            } else if (!t.contains("overdue", ignoreCase = true) && DUE.containsMatchIn(t)) {
                s.rule += "J7 due notice "
                return notTxn(s)
            }
        }
        if (IF_PAID.containsMatchIn(t)) { s.rule += "J8 if paid "; return notTxn(s) }
        if (DUE_BY.containsMatchIn(t)) { s.rule += "J9 due by "; return notTxn(s) }
        if (AUTOPAY.containsMatchIn(t) || AUTOPAY2.containsMatchIn(t)) { s.rule += "J10 autopay "; return notTxn(s) }
        return true
    }

    /** Rule 4: "has been reversed" flips the direction found by Stage H. */
    fun post(s: ParseState) {
        val m = REVERSED.find(s.text) ?: return
        val follow = FinanceText.after(s.text, m.range.last + 1, 12).lowercase()
        if ("debit" in follow && s.direction == TxnDirection.CREDIT) {
            s.direction = TxnDirection.DEBIT
            s.kind = TxnKind.REVERSAL
            s.rule += "J4 reversed->debit "
        } else if (s.direction == TxnDirection.DEBIT) {
            s.direction = TxnDirection.CREDIT
            s.paymentMode = "Credited"
            s.kind = TxnKind.REVERSAL
            s.rule += "J4 reversed->credit "
        }
    }

    private fun notTxn(s: ParseState): Boolean {
        s.status = ParseStatus.NOT_TXN
        return false
    }
}
