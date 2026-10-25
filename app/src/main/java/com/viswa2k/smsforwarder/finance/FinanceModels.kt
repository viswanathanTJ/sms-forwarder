// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/FinanceModels.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import kotlin.math.roundToLong

/** Debit / credit (spec 05 §3.2 `G` / `status_int`: 1 = debit, 2 = credit). */
enum class TxnDirection(val code: Int, val word: String) {
    NONE(0, ""),
    DEBIT(1, "debited"),
    CREDIT(2, "credited");

    companion object {
        fun fromCode(code: Int): TxnDirection = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * Outcome class of one parse (spec 05 §3.3 / §3.14, rebuild enum `TxnStatus`) plus the balance-only
 * code 30. [code] is the original output number.
 */
enum class ParseStatus(val code: Int) {
    /** Not a transaction (0): self-transfer, reminder look-alike, sender gate, no amount… */
    NOT_TXN(0),
    /** Failed / declined (3): stored for display, cancels the matching debit. */
    FAILED(3),
    /** Valid transaction (34). */
    OK(34),
    /** Balance-only SMS (30): updates an account balance, no transaction row. */
    BALANCE_ONLY(30),
}

/**
 * Derived transaction kind (spec 05 §3.14 `TxnKind`): from the card / balance / failure stages and the
 * payment mode.
 */
enum class TxnKind {
    DEBIT, CREDIT, REFUND, REVERSAL, ATM_WITHDRAWAL, CC_PAYMENT_RECEIVED, WALLET_LOAD;
}

/** Card type names as the original stores them (spec 05 §3.5 `f()`). */
object CardTypes {
    const val CREDIT = "Credit card"
    const val DEBIT = "Debit card"
    const val MEAL = "Meal card"
    const val PREPAID = "Prepaid card"
}

/** Merchant category names (05a `getStringChangeMerchantCategory`, spec 05 §3.10). */
object TxnCategories {
    const val TRANSPORTATION = "Transportation"
    const val TRAVEL = "Travel"
    const val FUEL = "Fuel"
    const val SHOPPING = "Shopping"
    const val TRAVEL_HOTEL = "Travel/Hotel"
    const val ESSENTIAL_SERVICE = "Essential Service"
    const val HEALTHCARE = "Healthcare"
    const val UTILITY_BILL = "Utility Bill"
    const val HOTEL = "Hotel"
    const val FOOD = "Food"
    const val ENTERTAINMENT = "Entertainment"
    const val INSURANCE = "Insurance"
    const val MERCHANT_PAY = "Merchant Pay"
    const val FUND_TRANSFER = "Fund Transfer"
    const val STANDING_INSTRUCTION = "Standing Instruction"
    const val PERSONAL_CONTACTS = "Personal Contacts"
    const val CREDIT_CARD_BILL = "Credit Card Bill"
    const val CHALLAN = "Challan"
    const val EDUCATION = "Education"
    const val GROCERY = "Grocery"
    const val INVESTMENT = "Investment"
    const val JEWELLERY = "Jewellery"
    const val LIFESTYLE = "Lifestyle"
    const val LOAN = "Loan"
    const val PET = "Pet"
    const val PROFESSIONAL = "Professional"
    const val SPORTS = "Sports"
    const val TAX = "Tax"
    const val WELLNESS = "Wellness"
    const val FOREX = "Forex"
    const val SELF_TRANSFER = "Self Transfer"
    const val ONLINE_GROCERY = "Online Grocery"
    const val EMI = "EMI"
    const val CASHBACK = "Cashback"
    const val UNDETECTED = "Undetected"
    const val INCOME = "Income"

    /** Categories the daily summary and the spend totals exclude (spec 05 §3.15 step 12). */
    val SUMMARY_EXCLUDED = listOf(SELF_TRANSFER, CREDIT_CARD_BILL)

    /** Category → icon name (05a getStringChangeMerchantCategory 34–70), for 10b's icon mapping. */
    val ICONS: Map<String, String> = mapOf(
        HEALTHCARE to "logo_health_category",
        SHOPPING to "logo_shopping_category",
        MERCHANT_PAY to "logo_merchant_category",
        FUND_TRANSFER to "logo_fundst_category",
        PERSONAL_CONTACTS to "logo_personal_contact",
        FUEL to "logo_fuel_category",
        TRAVEL_HOTEL to "logo_travel_hotel_category",
        TRAVEL to "logo_travel_category",
        FOOD to "logo_food_category",
        ENTERTAINMENT to "logo_entertainment_category",
        TRANSPORTATION to "logo_transport_category",
        ESSENTIAL_SERVICE to "logo_ess_category",
        UTILITY_BILL to "logo_utility_category",
        HOTEL to "logo_hotel_category",
        INSURANCE to "logo_insurance_category",
        STANDING_INSTRUCTION to "logo_standing_category",
        CREDIT_CARD_BILL to "logo_cc_bill_category",
        CHALLAN to "logo_challan_category",
        EDUCATION to "logo_education_category",
        GROCERY to "logo_grocery_category",
        ONLINE_GROCERY to "logo_grocery_category",
        INVESTMENT to "logo_investment_category",
        JEWELLERY to "logo_jewellery_category",
        LIFESTYLE to "logo_lifestyle_category",
        LOAN to "logo_loan_category",
        PET to "logo_pet_category",
        PROFESSIONAL to "logo_professional_category",
        SPORTS to "logo_sports_category",
        TAX to "logo_tax_category",
        WELLNESS to "logo_welness_category",
        FOREX to "logo_forex_category",
        UNDETECTED to "logo_no_category",
        SELF_TRANSFER to "logo_self_transfer_category",
        EMI to "logo_emi_category",
    )
}

/**
 * Result of [TransactionParser.parse] (spec 05 §3.2). Amounts are rupees as parsed ([amount]) and in
 * paise ([amountMinor]); `null` balance / outstanding mean "not in the SMS" (the original's −1).
 */
data class ParsedTransaction(
    val status: ParseStatus,
    /** Sender as resolved by [BankSenderResolver] (bank name, logo, isBank). */
    val sender: BankSender,
    /** Transaction amount (r), 0 when none. */
    val amount: Double = 0.0,
    val direction: TxnDirection = TxnDirection.NONE,
    /** Available balance, or the available credit limit for a card (s). */
    val balance: Double? = null,
    /** Card outstanding (t). */
    val outstanding: Double? = null,
    /** Account last digits (u); "-1" when the transaction belongs to the card ([cardLast4]). */
    val accountLast4: String? = null,
    /** Digits of the account the SMS calls "your" account (o). */
    val ownAccountDigits: String? = null,
    /** Non-own accounts (b, c), usually the beneficiary. */
    val otherAccount1: String? = null,
    val otherAccount2: String? = null,
    /** Card last digits (v). */
    val cardLast4: String? = null,
    /** "Credit card" / "Debit card" / "Meal card" / "Prepaid card" (B). */
    val cardType: String? = null,
    /** Stage E decided the account is a credit card (available limit / outstanding / balance transfer). */
    val accountType: String? = null,
    /** Stage D "not sure" masked digits ("NS", A). */
    val accountUncertain: Boolean = false,
    /** "loan" / "wallet" / "fastag" / "Fixed Deposit" (a), null = none ("-"). */
    val subType: String? = null,
    /** Merchant / payee display name, title-cased (x). */
    val merchant: String = "",
    /** Payment mode (D): "UPI", "NEFT", "ATM", "Debited"… */
    val paymentMode: String = "",
    /** Merchant category (E). */
    val category: String = TxnCategories.UNDETECTED,
    /** UPI reference number (F). */
    val upiRef: String? = null,
    /** Payee UPI handle (VPA), when one was in the SMS. */
    val vpa: String? = null,
    /** "Credited to … XX1234" target account digits (j()). */
    val toAccount: String? = null,
    val kind: TxnKind = TxnKind.DEBIT,
    /** Bank debit paying this credit card's bill: the builder books the matching card credit. */
    val paidCardLast4: String? = null,
    /** Failure / negated success: delete the matching debit of the previous hour (spec 05 §3.13). */
    val cancelsMatchingDebit: Boolean = false,
    /** Why the parse ended as it did (debug, e.g. "H3 main", "J7 due notice"). */
    val rule: String = "",
) {
    val amountMinor: Long get() = Money.toMinor(amount)
    val balanceMinor: Long? get() = balance?.let { Money.toMinor(it) }
    val outstandingMinor: Long? get() = outstanding?.let { Money.toMinor(it) }

    /** True when the account is (or the transaction went through) a credit card. */
    val isCreditCard: Boolean
        get() = cardType == CardTypes.CREDIT || accountType == CardTypes.CREDIT

    /** Key digits used to attach the transaction to an account: the card, else the account. */
    val accountKey: String?
        get() = cardLast4?.takeIf { it.isNotEmpty() } ?: accountLast4?.takeIf { it.isNotEmpty() && it != "-1" }
}

/** Money helpers (spec 05 §0 locale: en_IN, "₹ 1,23,456", trailing ".00" stripped). */
object Money {
    /**
     * Sanity bound for any parsed amount / balance / outstanding: INR 10^10 (1,000 crore). A bigger value is a
     * malformed or hostile SMS (a long digit run); keeping it out stops SQLite SUM() from overflowing Long.
     */
    const val MAX_RUPEES = 10_000_000_000.0

    fun inRange(rupees: Double): Boolean = rupees.isFinite() && kotlin.math.abs(rupees) <= MAX_RUPEES

    fun toMinor(rupees: Double): Long = (rupees * 100.0).roundToLong()

    fun fromMinor(paise: Long): Double = paise / 100.0

    /**
     * Formats [paise] as Indian currency: "₹ 1,23,456.50", "₹ 500" (".00" stripped), negative with a
     * leading "-". Implemented by hand (lakh grouping) so it does not depend on the device's locale data.
     */
    fun format(paise: Long): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100
        val fraction = (abs % 100).toInt()
        val digits = rupees.toString()
        val grouped = if (digits.length <= 3) digits else {
            val head = digits.dropLast(3)
            val tail = digits.takeLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }
        val frac = if (fraction == 0) "" else "." + fraction.toString().padStart(2, '0')
        return (if (negative) "-" else "") + "₹ " + grouped + frac
    }
}
