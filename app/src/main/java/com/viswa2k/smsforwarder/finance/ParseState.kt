// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/ParseState.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

/**
 * Mutable working state threaded through the parser stages (the original's `creditdebitdetails/b`
 * fields). [w] is the working text W: stages delete consumed tokens from it so later stages cannot
 * reuse them. Unlike the original (a lower-cased copy) W keeps the original case, because several
 * patterns are case-sensitive after an inline `(?i)` group (`…for…)[A-Z]`); every other match is
 * case-insensitive, so the difference is invisible otherwise.
 */
internal class ParseState(
    val sender: BankSender,
    /** Normalised text (original case), [FinanceText.prepare]. */
    val text: String,
) {
    var w: String = text

    // Stage A / C / D
    var accountLast4: String? = null
    var ownAccountDigits: String? = null
    var otherAccount1: String? = null
    var otherAccount2: String? = null
    var accountToken1: String? = null
    var accountToken2: String? = null
    var ownFound = false
    /** A second "your" account (self-transfer check, spec 05 §3.11 rule 1). */
    var secondOwnDigits: String? = null
    var accountUncertain = false
    var subType: String? = null

    // Stage B
    var cardLast4: String? = null
    var cardType: String? = null
    /** Credit card paid from a bank account (bill payment), when the SMS is the bank's debit. */
    var paidCardLast4: String? = null

    // Stage E
    var balance: Double? = null
    var outstanding: Double? = null
    var accountType: String? = null

    // Stage F / I / j
    var merchant: String = ""
    var merchantPlaceholder: String = ""
    var paymentMode: String = ""
    var modeKnown = false
    var vpa: String? = null
    var upiRef: String? = null
    var toAccount: String? = null
    var category: String? = null

    // Stage H / J
    var amount = 0.0
    var direction = TxnDirection.NONE
    var kind: TxnKind? = null
    var status: ParseStatus? = null
    var cancelsMatchingDebit = false
    var rule = ""

    val lower: String get() = w.lowercase()

    /** Deletes [token] (first occurrence) from W. */
    fun consume(token: String) {
        if (token.isEmpty()) return
        val i = w.indexOf(token)
        if (i >= 0) w = w.substring(0, i) + " " + w.substring(i + token.length)
    }

    val hasAccount: Boolean get() = !accountLast4.isNullOrEmpty() && accountLast4 != "-1"
    val hasCard: Boolean get() = !cardLast4.isNullOrEmpty()
}
