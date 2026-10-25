// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/ContentRules.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/**
 * Light-weight content detectors for the category of an important message (spec 04b §4.2 stages 6–9,
 * §3.1 codes 11/30/32/33) and the promotional rules. They only decide the *category*; the full
 * transaction / bill parsers (amounts, accounts, due dates) are Task 10.
 *
 * Vocabulary is authored for the rebuild (the original's promo cascade is not recoverable, spec 04b
 * §16.2), plus the delivery lists getStringOSP1/2/8 and the gaming list getNewCases19 from 05a.
 */
class ContentRules(lists: KeywordLists = KeywordLists.EMPTY) {

    private val osp1 = lists.item("getStringOSP1", 0).ifBlank {
        "received|placed|confirmed|shipped|delivered|dispatched|processed|scheduled|picked|booked|completed"
    }
    private val osp2 = lists.item("getStringOSP2", 0).ifBlank { "order|status|shipment|delivery|at" }
        .split('|').filter { it.length > 2 }.joinToString("|") // "at" alone is too common
    private val osp8 = lists["getStringOSP8"].filter { it.isNotBlank() }.ifEmpty {
        listOf("product", "gas", "cylinder", "DTDC", "Shadowfax", "ARRIVING", "blue", "dart")
    }.joinToString("|") { Regex.escape(it) }

    private val deliveryVerb = Regex("(?i)\\b($osp1)\\b")
    private val deliveryNoun = Regex("(?i)\\b($osp2|parcel|package|courier|awb|consignment|$osp8)")
    private val outForDelivery = Regex("(?i)\\b(out for delivery|arriving (today|tomorrow)|delivery (is )?scheduled|" +
        "will be delivered|has been delivered|was delivered|tracking (id|no|number))\\b")

    private val gaming = lists["getNewCases19"].let { l ->
        (7..13).map { l.getOrElse(it) { "" } }.filter { it.isNotBlank() }
    }.ifEmpty { listOf("poker", "teen", "patti", "Matka", "Games", "App", "Play") }
    private val gamingStrong = Regex("(?i)\\b(poker|teen\\s*patti|matka|rummy|casino|satta|betting|ludo)\\b")
    private val gamingWords = Regex("(?i)\\b(" + gaming.joinToString("|") { Regex.escape(it) } + ")\\b")

    /** True if [text] has an amount (₹ / Rs / INR). */
    fun hasAmount(text: String): Boolean = AMOUNT.containsMatchIn(text)

    /** Bank / wallet transaction wording with an amount (category 33). */
    fun isTransaction(text: String): Boolean =
        hasAmount(text) && TXN_VERB.containsMatchIn(text) && ACCOUNT_REF.containsMatchIn(text) &&
            !OFFER_AMOUNT.containsMatchIn(text)

    /** Bank-transaction wording (debit/credit + account) with an amount, as used by the fraud rules. */
    fun isBankTransactionWording(text: String): Boolean =
        hasAmount(text) && BANK_TXN.containsMatchIn(text) && ACCOUNT_REF.containsMatchIn(text)

    /** Balance / account alert without a transaction (category 30). */
    fun isBalanceAlert(text: String): Boolean =
        BALANCE.containsMatchIn(text) && hasAmount(text) && !TXN_VERB.containsMatchIn(text)

    /** Bill due / statement / travel / booking (category 32). */
    fun isSmartAlert(text: String): Boolean =
        (BILL_WORD.containsMatchIn(text) && DUE_WORD.containsMatchIn(text)) ||
            TRAVEL.containsMatchIn(text) || BOOKING.containsMatchIn(text)

    /** Order / delivery status (category 11; getStringOSP1/2/8). */
    fun isDelivery(text: String): Boolean =
        outForDelivery.containsMatchIn(text) ||
            (deliveryVerb.containsMatchIn(text) && deliveryNoun.containsMatchIn(text))

    /** Gaming / gambling marker with a link (numeric-header promo rules 91–93, getNewCases19). */
    fun isGamingPromo(text: String, hasLink: Boolean): Boolean =
        (hasLink || WWW.containsMatchIn(text)) &&
            (gamingStrong.containsMatchIn(text) || gamingWords.findAll(text).map { it.value.lowercase() }.toSet().size >= 2)

    /** Number of distinct promotional phrases in [text]. */
    fun promoScore(text: String): Int = PROMO.count { it.containsMatchIn(text) }

    companion object {
        /** Promo score at which a header message is promotional. */
        const val PROMO_THRESHOLD = 2

        private val opt = RegexOption.IGNORE_CASE
        val AMOUNT = Regex("(?i)((?:\\brs\\.?|\\binr\\.?|₹|\\brupees)\\s*[\\d,]*\\d(?:\\.\\d+)?|\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:rs|inr|rupees)\\b)")
        private val TXN_VERB = Regex(
            "\\b(debited|credited|withdrawn|spent|paid|received|transferred|sent|deposited|purchase|charged|" +
                "txn|transaction|refund(ed)?|reversed|dr\\.?|cr\\.?)\\b", opt,
        )
        private val BANK_TXN = Regex("\\b(debited|credited|withdrawn|deposited|transferred|txn|transaction)\\b", opt)
        private val ACCOUNT_REF = Regex(
            "(\\ba/c\\b|\\bacc(oun)?t\\b|\\bac\\b|\\bacct\\b|\\bcard\\b|\\bupi\\b|\\bwallet\\b|\\bvpa\\b|\\bimps\\b|" +
                "\\bneft\\b|\\brtgs\\b|[x*]{2,}\\d{3,})", opt,
        )
        private val OFFER_AMOUNT = Regex(
            "\\b(win|won|upto|up to|get|worth|offer|cashback of|loan of|limit of|eligible)\\b\\W{0,3}(rs|inr|₹)", opt,
        )
        private val BALANCE = Regex(
            "\\b(avl\\.? bal|avlbl bal|available balance|avail(able)? bal|bal(ance)? (is|:)|closing balance|" +
                "a/c balance|account balance|ledger balance)", opt,
        )
        private val BILL_WORD = Regex(
            "\\b(bill|statement|premium|emi|renewal|recharge|invoice|installment|instalment)\\b", opt,
        )
        private val DUE_WORD = Regex("\\b(due|overdue|pay by|last date|payable|expir\\w*|generated)\\b", opt)
        private val TRAVEL = Regex(
            "\\b(pnr|flight|boarding|departure|departs|train no|berth|coach|bus ticket|check-?in|e-?ticket)\\b", opt,
        )
        private val BOOKING = Regex(
            "\\b(booking (id|confirmed|is confirmed)|tickets? (booked|confirmed)|show ?time|movie|" +
                "appointment (is )?(confirmed|scheduled|booked))\\b", opt,
        )
        private val WWW = Regex("(?i)\\b(www|http)")

        /** Promotional phrases (each counts once). */
        private val PROMO = listOf(
            "\\b(offer|offers)\\b",
            "\\b(sale|mega sale|big sale)\\b",
            "\\bdiscount",
            "\\d+\\s*%\\s*(off|discount|cashback)",
            "\\bflat\\s*(rs\\.?|₹)?\\s*\\d+",
            "\\bcashback\\b",
            "\\b(coupon|voucher|promo ?code|use code)\\b",
            "\\b(shop|buy|order|book|grab|download|install|upgrade|subscribe|apply) now\\b",
            "\\bhurry\\b",
            "\\blimited (period|time|stock|offer)\\b",
            "\\b(last chance|ends (today|tonight|soon)|today only|don'?t miss)\\b",
            "\\bdeals?\\b",
            "\\b(free|freebie)\\b",
            "\\b(exclusive|special) (price|offer|deal)",
            "\\b(lowest|best) price\\b",
            "\\b(unsubscribe|opt[- ]?out|to stop (receiving|sms)|sms stop)\\b",
            "\\bt\\s*&\\s*c\\b",
            "\\b(starting|starts) (at|from)\\b",
            "\\bsave (up ?to|upto|rs|₹)",
            "\\b(new arrivals?|collection|launch(ed)?|introducing)\\b",
            "\\b(win|lucky draw|contest)\\b",
        ).map { Regex(it, opt) }
    }
}
