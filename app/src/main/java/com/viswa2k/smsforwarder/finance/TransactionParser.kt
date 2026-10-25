// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/TransactionParser.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.KeywordLists
import java.text.Normalizer

/**
 * The transaction SMS parser (spec 05 §3): `parse(sender, body)` → [ParsedTransaction]. Pure Kotlin,
 * thread-safe (no shared mutable state), built once per process from the 05a keyword lists.
 *
 * Pipeline (§3.1): sender identification and gates → text preparation ([FinanceText.prepare]) →
 * A account → B card → C remaining account → D not-sure → E balance → F merchant/mode →
 * J failures (rules 1–3, 5–10) → H amount/direction → J rule 4 (reversal flip) → I payee/UPI → j() →
 * G category → final status (§3.14).
 *
 * Deviations / interpretations (also in the Task 10a report):
 * - J runs for every message, split around H (see [FailureStage]).
 * - Stage F's "mode word as placeholder" (step 2) does not block later merchant rules; it becomes the
 *   merchant only when F, I and j() found nothing (e.g. "ATM", "NEFT").
 * - The first brand-sender regex (display name, §3.1 step 5; branch body lost) suppresses non-bank
 *   senders the same way as the sender-id list.
 * - Stage H rule 2 ("sender class 4") applies to non-bank senders.
 * - Stage H's main pattern also ends at " to / at / for / in " and at the end of text.
 */
class TransactionParser(lists: KeywordLists, val senders: BankSenderResolver = BankSenderResolver(lists)) {

    private val merchantStages = MerchantStages(lists)
    private val categorizer = MerchantCategorizer(lists)

    private val accountTokens: List<String> = lists["getStringENTERCRD"].take(7).filter { it.isNotBlank() }
        .ifEmpty { listOf("card", "acc", "ac", "a/c", "**", "..", "xx") }

    private val brandNameGate = Regex(
        "(?i)(MakeMyTrip|Lenskart|bsnl|SHOPPERS STOP|Ibibo|PhonePe|Flipkart|jio|^ola|MYNTRA|1mg|sodexo|nsdl|BPCL|petroleum|" +
            "iNDIAN OIL|cdsl|insurance|Pluxee|cars24|Sharekhan|^lic$|mutual|Zerodha|HP Gas|hospital|GyFTR|Adani Electricity|" +
            "Broadnet|Hathway|Prudential|Asset|Invest|Nykaa|Securit|Reward|Voucher|Digital|Tanishq|Cinema|^VI$)",
    )
    private val senderIdGate = Regex(
        "(?i)(" + (lists["getStringSendersENC"].filter { it.isNotBlank() && it != "tata" }.joinToString("|").ifBlank {
            "jio|ola|fchrge|flpkrt|phonpe|amazon|idea|dth|goib|tsky|mpc|fctzen|bsnl|myntra|LSKART"
        }) + ")",
    )

    /** Parses one SMS. Never throws: any internal error yields [ParseStatus.NOT_TXN] (spec 05 §3.1 step 9). */
    fun parse(address: String, body: String): ParsedTransaction {
        val sender = senders.resolve(address)
        return try {
            parseInternal(sender, body)
        } catch (e: RuntimeException) {
            ParsedTransaction(ParseStatus.NOT_TXN, sender, rule = "exception ${e::class.simpleName}")
        } catch (e: StackOverflowError) {
            ParsedTransaction(ParseStatus.NOT_TXN, sender, rule = "regex overflow")
        }
    }

    private fun notTxn(sender: BankSender, rule: String) = ParsedTransaction(ParseStatus.NOT_TXN, sender, rule = rule)

    private fun parseInternal(sender: BankSender, body: String): ParsedTransaction {
        // Routing (§2.3): full mobile numbers and all-digit senders are never parsed.
        if (!sender.isBusinessRoute || sender.source == BankSender.Source.PHONE) return notTxn(sender, "gate: phone sender")
        if (sender.isDigitsOnly) return notTxn(sender, "gate: digits-only sender")
        // Step 4: account-token gate.
        val lowerNoUrls = FinanceText.stripUrls(Normalizer.normalize(body, Normalizer.Form.NFKC)).lowercase()
        if (accountTokens.none { it in lowerNoUrls }) return notTxn(sender, "gate: no account token")
        // Step 5: brand-sender gate.
        if (!sender.isBank && (senderIdGate.containsMatchIn(sender.id) || brandNameGate.containsMatchIn(sender.name))) {
            return notTxn(sender, "gate: brand sender")
        }

        val s = ParseState(sender, FinanceText.prepare(body))
        AccountStages.stageA(s)
        AccountStages.stageB(s)
        cardBillPayment(s)
        AccountStages.stageC(s)
        AccountStages.stageD(s)
        BalanceStage.run(s)
        merchantStages.stageF(s)
        if (!FailureStage.pre(s)) return finish(s)
        if (s.amount == 0.0) AmountStage.run(s)
        // A failed / declined card transaction is a debit unless the SMS really credits money.
        if (s.status == ParseStatus.FAILED && s.hasCard && s.direction == TxnDirection.CREDIT && !REAL_CREDIT.containsMatchIn(s.w)) {
            s.direction = TxnDirection.DEBIT
            s.rule += "Jcard->debit "
        }
        if (s.status == ParseStatus.NOT_TXN) return finish(s)
        FailureStage.post(s)
        merchantStages.stageI(s)
        merchantStages.stepJ(s)
        val cat = categorizer.categorize(s.merchant, s.text, s.amount, s.category)
        s.merchant = cat.merchant
        s.category = cat.category
        return finish(s)
    }

    /**
     * Review fix (bank → credit-card bill payment): "debited from your a/c XX1234 towards Credit Card
     * XX9876 payment" belongs to the bank account; the card is only the payee. The card digits move to
     * [ParseState.paidCardLast4] and the category is Credit Card Bill (the builder also books the
     * payment credit on the card).
     */
    private fun cardBillPayment(s: ParseState) {
        val card = s.cardLast4 ?: return
        val account = s.ownAccountDigits ?: s.otherAccount1 ?: return
        if (account == card || !CARD_BILL_PAYMENT.containsMatchIn(s.text)) return
        s.paidCardLast4 = card
        s.cardLast4 = null
        s.cardType = null
        s.accountLast4 = account
        s.category = TxnCategories.CREDIT_CARD_BILL
        s.rule += "ccbill "
    }

    private val REAL_CREDIT = Regex("(?i)(credited|refund|deposit|received)")

    private val CARD_BILL_PAYMENT = Regex(
        "(?i)(towards|to|for)\\W+(your\\W+)?([a-z]+\\W+){0,3}credit\\s*card\\W+(no\\.?\\W*|ending\\W*|a/c\\W*)?XX[0-9]{2,}",
    )

    /** Final status (spec 05 §3.14) and the derived [TxnKind]. */
    private fun finish(s: ParseState): ParsedTransaction {
        // Sanity bound (final review C-I4): an absurd amount is not a transaction; absurd balances are dropped.
        if (!Money.inRange(s.amount)) return notTxn(s.sender, "gate: amount out of range")
        if (s.balance?.let { Money.inRange(it) } == false) s.balance = null
        if (s.outstanding?.let { Money.inRange(it) } == false) s.outstanding = null
        val status = when {
            s.status == ParseStatus.NOT_TXN -> ParseStatus.NOT_TXN
            s.amount > 0.0 && s.direction != TxnDirection.NONE -> s.status ?: ParseStatus.OK
            s.balance != null && (s.hasAccount || s.hasCard || s.accountLast4 == "-1") && s.status == null -> ParseStatus.BALANCE_ONLY
            else -> ParseStatus.NOT_TXN
        }
        val kind = s.kind ?: when {
            s.direction == TxnDirection.CREDIT && (s.subType == "wallet" || s.cardType == CardTypes.PREPAID) &&
                s.text.contains("load", ignoreCase = true) -> TxnKind.WALLET_LOAD
            s.direction == TxnDirection.DEBIT && (s.paymentMode == "ATM" || s.paymentMode == "CASH") -> TxnKind.ATM_WITHDRAWAL
            s.direction == TxnDirection.CREDIT -> TxnKind.CREDIT
            else -> TxnKind.DEBIT
        }
        val isTxn = status == ParseStatus.OK || status == ParseStatus.FAILED
        return ParsedTransaction(
            status = status,
            sender = s.sender,
            amount = if (isTxn) s.amount else 0.0,
            direction = if (isTxn) s.direction else TxnDirection.NONE,
            balance = s.balance,
            outstanding = s.outstanding,
            accountLast4 = s.accountLast4,
            ownAccountDigits = s.ownAccountDigits,
            otherAccount1 = s.otherAccount1,
            otherAccount2 = s.otherAccount2,
            cardLast4 = s.cardLast4,
            cardType = s.cardType,
            accountType = s.accountType,
            accountUncertain = s.accountUncertain,
            subType = s.subType,
            merchant = if (isTxn) s.merchant else "",
            paymentMode = s.paymentMode,
            category = s.category ?: TxnCategories.UNDETECTED,
            upiRef = s.upiRef,
            vpa = s.vpa,
            toAccount = s.toAccount,
            kind = kind,
            cancelsMatchingDebit = s.cancelsMatchingDebit && status == ParseStatus.FAILED && s.direction != TxnDirection.CREDIT,
            paidCardLast4 = if (isTxn && s.direction == TxnDirection.DEBIT) s.paidCardLast4 else null,
            rule = s.rule.trim(),
        )
    }
}
