// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/MerchantCategorizer.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.KeywordLists

/**
 * Stage G (`a.a()` + helpers o/q/p, spec 05 §3.10): merchant clean-up and category, from the 05a
 * category lists. Order: early rules → merchant clean-up → hard-coded brand rules → regex lists →
 * known-merchant brand table (Category6/7) → stem table (Category1/2) → Category3 → product table
 * (Category8/9) → dictionary test (Category4/5) → gateway fallback → Undetected.
 *
 * Interpretation: the regex lists are tested against the merchant when there is one, and against the
 * body only when no merchant was found (testing merchant + body made generic bank wording such as
 * "become" / "station" / "institute" pick categories).
 */
class MerchantCategorizer(lists: KeywordLists) {

    private data class TableEntry(val regex: Regex, val category: String)

    private val regexLists: List<Pair<Regex, String>> = buildList {
        fun add(name: String, category: String) {
            val v = lists.item(name, 0)
            if (v.isNotBlank()) add(Regex("(?i)($v)") to category)
        }
        add("getStringChangeMerchantCategoryFood", TxnCategories.FOOD)
        add("getStringChangeMerchantCategoryShopping", TxnCategories.SHOPPING)
        add("getStringChangeMerchantCategoryTravel", TxnCategories.TRAVEL)
        add("getStringChangeMerchantCategoryTravelHotel", TxnCategories.TRAVEL_HOTEL)
        add("getStringChangeMerchantCategoryEducation", TxnCategories.EDUCATION)
        add("getStringChangeMerchantCategoryEntertainment", TxnCategories.ENTERTAINMENT)
        add("getStringChangeMerchantCategoryInvestment", TxnCategories.INVESTMENT)
        add("getStringChangeMerchantCategoryHospital", TxnCategories.HEALTHCARE)
        add("getStringChangeMerchantCategoryTransportation", TxnCategories.TRANSPORTATION)
        add("getStringMerchantCategoryEleUtilityBill", TxnCategories.UTILITY_BILL)
        add("getStringChangeMerchantCategoryUtilityBill", TxnCategories.UTILITY_BILL)
        add("getStringChangeMerchantCategoryInsurance", TxnCategories.INSURANCE)
        add("getStringChangeMerchantCategoryJewellery", TxnCategories.JEWELLERY)
        add("getStringChangeMerchantCategoryProfessional", TxnCategories.PROFESSIONAL)
        add("getStringChangeMerchantCategorySports", TxnCategories.SPORTS)
        add("getStringChangeMerchantCategoryWellness", TxnCategories.WELLNESS)
        add("getStringChangeMerchantCategoryEMI", TxnCategories.EMI)
        val mp = lists.item("getStringChangeMerchantCategoryMerchantPay", 0)
        add(Regex("(?i)(" + (if (mp.isBlank()) "" else "$mp|") + "bharatpay|paytm\\s*QR|Merchant\\sq)") to TxnCategories.MERCHANT_PAY)
    }

    /** Category6 → Category7 (≈747 brand names) and Category8 → Category9 (≈816 product names). */
    private val brandTable: List<TableEntry> by lazy { table(lists["getStringChangeMerchantCategory6"], lists["getStringChangeMerchantCategory7"]) }
    private val productTable: List<TableEntry> by lazy { table(lists["getStringChangeMerchantCategory8"], lists["getStringChangeMerchantCategory9"]) }

    private fun table(names: List<String>, categories: List<String>): List<TableEntry> = names.mapIndexedNotNull { i, raw ->
        val name = raw.trim()
        if (name.length < 2) return@mapIndexedNotNull null
        val words = name.split(Rx.SPACES).filter { it.isNotEmpty() }
        val pattern = if (words.size == 1) {
            "^(?i)" + Regex.escape(words[0])
        } else {
            "^(?i)" + words.dropLast(1).joinToString("(\\W+|)") { Regex.escape(it) } + "(\\W+|)" + Regex.escape(words.last().take(2))
        }
        val cat = categories.getOrElse(i) { "" }.trim().ifEmpty { TxnCategories.MERCHANT_PAY }
        TableEntry(Regex(pattern), cat)
    }

    /** Stem table: the `|` lists Category1 (re-joined: one stem, `Q\d{4,}`, contains a comma) / Category2. */
    private val stems: List<Pair<String, String>> = run {
        val s = lists["getStringChangeMerchantCategory1"].joinToString(",").split('|')
        val c = lists.item("getStringChangeMerchantCategory2", 0).split('|')
        s.mapIndexedNotNull { i, stem -> if (stem.isBlank() || i >= c.size) null else stem to c[i].let { if (it == "Jeweller") TxnCategories.JEWELLERY else it } }
    }
    private val stemRegex: Regex? = stems.takeIf { it.isNotEmpty() }?.let { list ->
        Regex("(?i)(^|\\W)(" + list.joinToString("|") { "(" + it.first + ")" } + ")(\\w+|)(\\W|$)")
    }

    private val cat3Words: List<String> = lists.item("getStringChangeMerchantCategory3", 0).ifBlank { "motor|Bar|Car|AND" }.split('|')
    private val stemMatchers: List<Pair<Regex, String>> = stems.map { Regex("(?i)^(${it.first})$") to it.second }
    private val cat3Matchers: List<Regex?> by lazy {
        cat3Words.map { w -> if (w.isBlank()) null else Regex("(?i)(^|\\W)${Regex.escape(w)}(\\W|$)") }
    }
    private val cat3Cats: List<String> = lists.item("getStringChangeMerchantCategory3", 1)
        .ifBlank { "Transportation|Food|Transportation|Merchant Pay" }.split('|')

    private val dictionary: Set<String> by lazy {
        (lists["getStringChangeMerchantCategory4"] + lists["getStringChangeMerchantCategory5"])
            .map { it.trim().lowercase() }.filter { it.length >= 3 }.toHashSet()
    }

    /** Result of [categorize]: cleaned display merchant and category. */
    data class Result(val merchant: String, val category: String)

    /**
     * Categorises [merchant] given the SMS [body] (normalised) and [amount]. [preset] is a category an
     * earlier step already decided (Personal Contacts), which only the early rules override.
     */
    fun categorize(merchant: String, body: String, amount: Double, preset: String? = null): Result {
        val r = categorizeRaw(merchant, body, amount, preset)
        // "hotel" with an amount over 5000 is a stay, not a meal (Category3[2]; applied after every list).
        return if (r.category == TxnCategories.FOOD && amount > 5000 && r.merchant.contains("hotel", ignoreCase = true)) {
            r.copy(category = TxnCategories.TRAVEL)
        } else r
    }

    private fun categorizeRaw(merchant: String, body: String, amount: Double, preset: String?): Result {
        // 1. Early rules.
        if (EARLY_CC_BILL.containsMatchIn(body)) return Result(display(clean(merchant)), TxnCategories.CREDIT_CARD_BILL)
        if (EARLY_FUND_TRANSFER.containsMatchIn(body) && merchant.isBlank()) return Result("", TxnCategories.FUND_TRANSFER)
        if (preset != null) return Result(display(clean(merchant)), preset)
        // 2. Clean the merchant.
        var m = clean(merchant)
        // 3. Hard-coded brand rules.
        brandRule(m)?.let { (name, cat) -> return Result(name ?: display(m), cat) }
        // 4. Regex lists.
        val target = m.ifBlank { body }
        for ((re, cat) in regexLists) if (re.containsMatchIn(target)) return Result(display(m), cat)
        if (m.isBlank()) return Result("", TxnCategories.UNDETECTED)
        // 5. o(): known-merchant tables.
        if (m.equals("cash", true) || m.length < 3) return Result(display(m), TxnCategories.UNDETECTED)
        val stripped = TRAILING_SHORT.find(m)?.takeIf { !it.groupValues[2].trim().equals("qr", true) }
            ?.let { m.substring(0, it.range.first).trim() } ?: m
        brandTable.firstOrNull { it.regex.containsMatchIn(stripped) }?.let { return Result(display(m), it.category) }
        stemRegex?.find(stripped)?.let { hit ->
            val word = hit.groupValues[2]
            val cat = stemMatchers.firstOrNull { it.first.matches(word) }?.second ?: TxnCategories.MERCHANT_PAY
            val adjusted = if (cat == TxnCategories.FOOD && word.equals("hotel", true) && amount > 5000) TxnCategories.TRAVEL else cat
            return Result(display(m), adjusted)
        }
        cat3Matchers.forEachIndexed { i, re ->
            if (re != null && re.containsMatchIn(stripped)) return Result(display(m), cat3Cats.getOrElse(i) { TxnCategories.MERCHANT_PAY })
        }
        // 6. q(): product / merchant table.
        productTable.firstOrNull { it.regex.containsMatchIn(stripped) }?.let { return Result(display(m), it.category) }
        // 7. p(): dictionary test (small shop heuristic).
        if (stripped.split(Rx.NON_WORDS).any { it.length >= 3 && it.lowercase() in dictionary }) {
            return Result(display(m), TxnCategories.MERCHANT_PAY)
        }
        // 8. Gateway fallback.
        if (GATEWAY.containsMatchIn(merchant)) return Result(display(m), TxnCategories.MERCHANT_PAY)
        return Result(display(m), TxnCategories.UNDETECTED)
    }

    /** Merchant clean-up (spec 05 §3.10 step 2). */
    fun clean(merchant: String): String {
        var m = merchant.trim()
        m = m.replace(Rx.WORD_STAR_DIGITS, "$1")
        m = m.replace(Rx.LEADING_YOUR, "")
        m = m.replace(PREFIX, "")
        m = m.replace(LEADING_CONNECTOR, "")
        m = m.replace(Rx.STARS, " ")
        // Rebuild hygiene: leading mode words, trailing "for Rs …" / "from …" / "by …" tails, and results
        // made only of filler words ("A/c", "was not successful") are not merchants.
        m = m.replace(LEADING_MODE, "")
        m = m.replace(TRAILING_TAIL, "")
        m = m.replace(Rx.SPACES, " ").trim().trim('.', ',', '-', ':', '/')
        if (JUNK_START.containsMatchIn(m)) return ""
        if (m.split(Rx.NON_ALNUM_SLASH).filter { it.isNotEmpty() }.all { it.lowercase() in FILLER }) return ""
        return m
    }

    private fun display(m: String): String {
        if (m.isBlank()) return ""
        if (m.uppercase() in ACRONYMS) return m.uppercase()
        RENAMES.forEach { (re, to) -> if (re.containsMatchIn(m)) return to }
        return FinanceText.titleCase(m)
    }

    private fun brandRule(m: String): Pair<String?, String>? {
        if (m.isBlank()) return null
        val l = m.lowercase()
        return when {
            l == "self" -> "Self" to TxnCategories.SELF_TRANSFER
            "fastag" in l -> null to TxnCategories.TRANSPORTATION
            "eazydiner" in l -> null to TxnCategories.FOOD
            "ixigo" in l -> null to TxnCategories.TRAVEL_HOTEL
            DISNEY.containsMatchIn(m) -> null to TxnCategories.ENTERTAINMENT
            "maxlif" in l -> "Max Life Insurance" to TxnCategories.INSURANCE
            "jewel" in l -> null to TxnCategories.JEWELLERY
            "delhivery" in l -> null to TxnCategories.ESSENTIAL_SERVICE
            "salon" in l -> null to TxnCategories.WELLNESS
            listOf("grofers", "instamart", "bb daily", "zepto", "bigbasket").any { it in l } -> null to TxnCategories.ONLINE_GROCERY
            "swiggy" in l -> null to TxnCategories.FOOD
            SHELL.containsMatchIn(m) -> null to TxnCategories.FUEL
            EPF.containsMatchIn(m) -> null to TxnCategories.INVESTMENT
            "angel one mf" in l -> null to TxnCategories.INVESTMENT
            "bigtree" in l -> null to TxnCategories.ENTERTAINMENT
            "pharmeasy" in l -> null to TxnCategories.HEALTHCARE
            "starbucks" in l -> null to TxnCategories.FOOD
            "yatra" in l -> null to TxnCategories.TRAVEL_HOTEL
            "bruhat" in l -> null to TxnCategories.TAX
            ICCL.containsMatchIn(m) -> "ICCL" to TxnCategories.INVESTMENT
            "one97" in l -> "One97 Communication" to TxnCategories.MERCHANT_PAY
            else -> null
        }
    }

    companion object {
        private val DISNEY = Regex("(?i)disney\\W*\\+?\\W*hotstar")
        private val SHELL = Regex("(?i)(^|\\W)shell(\\W|$)")
        private val EPF = Regex("(?i)(^|\\W)epf(\\W|$)|empl\\w*\\W*prov")
        private val ICCL = Regex("(?i)indian\\W+Clearing\\W+C")
        private val EARLY_CC_BILL = Regex(
            "(?i)(card(\\W+|)to\\s*card|credit\\s*card\\s*(bill|dues)|payment\\s*(of\\s*(rs|inr)\\W*[0-9,.]+\\s*)?(has been |is |was )?received\\s*(on|towards|for)\\s*(your\\s*)?(\\w+\\s*){0,3}credit\\s*card)",
        )
        private val EARLY_FUND_TRANSFER = Regex("(?i)(\\Waeps\\W|fund(s|)\\s*transfer)")
        private val TRAILING_SHORT = Regex("(\\W)([a-zA-Z]{1,3}(\\W|)$)")
        private val GATEWAY = Regex("(?i)^(raz|msw)(\\*|_)")
        private val PREFIX = Regex(
            "(?i)^(rzp|razp|razorpay|raz|msw|pine|payu|billdesk|billdk|ccavenue|cca|paytm|phonepe|gpay|bharatpe|mbk|cf|ombk|ok|pg|pyu)(\\*|_|\\.|\\s+)",
        )
        private val LEADING_MODE = Regex("(?i)^(imps|neft|rtgs|upi|nach|ach|ecs)(\\s*(cr|dr))?\\W+(to\\s+|from\\s+)?(?=\\w)")
        private val TRAILING_TAIL = Regex("(?i)\\s+((for|of)\\s+(rs|inr)\\b|(from|by|via|using|thru|through)\\s).*$")
        private val JUNK_START = Regex("(?i)^(ref|refno|ref no|utr|rrn|upi ref|txn id|transaction id)\\b")
        private val FILLER = setOf(
            "a/c", "ac", "acc", "acct", "account", "your", "was", "is", "has", "been", "not", "successful", "the", "to",
            "from", "of", "on", "in", "at", "by", "for", "with", "and", "no", "number", "bank", "rs", "inr", "txn",
        )
        private val ACRONYMS = setOf("UPI", "NEFT", "IMPS", "RTGS", "ATM", "POS", "NACH", "CASH", "ECS")
        private val LEADING_CONNECTOR = Regex("(?i)^(is|on|at|sender|via|by|in|ib|trf|trx|to|for|with)\\s+")
        private val RENAMES: List<Pair<Regex, String>> = listOf(
            Regex("(?i)^flipkar") to "Flipkart",
            Regex("(?i)^bookmys") to "BookMyShow",
            Regex("(?i)^google\\s*play") to "Google Playstore",
            Regex("(?i)^makemytr") to "MakeMyTrip",
            Regex("(?i)^tata1mghealth") to "Tata 1mg",
            Regex("(?i)^interglobe avi") to "IndiGo Airlines",
            Regex("(?i)^bharat sanchar") to "BSNL",
            Regex("(?i)^the indian hotel") to "IHCL Taj Hotels",
            Regex("(?i)^dreamplug") to "CRED",
            Regex("(?i)^niyoglobal") to "Niyo Global",
            Regex("(?i)^urbanclap") to "Urbanclap",
            Regex("(?i)^fashnear") to "Meesho",
            Regex("(?i)^smartshift") to "Porter",
            Regex("(?i)^gyft") to "GyFTR",
            Regex("(?i)^onecardf") to "OneCard Federal",
            Regex("(?i)^finzoom") to "INDmoney",
            Regex("(?i)^bigbask") to "BigBasket",
            Regex("(?i)^myntr") to "Myntra",
            Regex("(?i)^zomato") to "Zomato",
            Regex("(?i)^swiggy") to "Swiggy",
            Regex("(?i)^amazon") to "Amazon",
            Regex("(?i)^irctc") to "IRCTC",
            Regex("(?i)^one97") to "One97 Communication",
        )
    }
}
