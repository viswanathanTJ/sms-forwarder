// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/FraudRules.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import com.viswa2k.smsforwarder.classify.FraudLevel

/**
 * Result of the fraud / content-blocking rules.
 * [level] NONE with [block] true is a "block, no class" hit (plain spam, e.g. the Devanagari loan/prize
 * group). [reason] is the debug rule id (spec 04b §3.3).
 */
data class FraudHit(val level: FraudLevel, val reason: Int, val block: Boolean = true)

/** What the fraud rules see of a message (prepared once by [MessageClassifier]). */
class FraudContext(
    val sender: SenderInfo,
    /** Folded body with links blanked (keyword copy). */
    val text: String,
    /** Folded body including links. */
    val fullText: String,
    val links: List<Link>,
    /** Body contained styled Unicode / look-alike letters (count of changed characters). */
    val styledChars: Int,
    /** Sender is a known bank / brand / government header (exempt from link and look-alike-bank rules). */
    val knownSender: Boolean,
) {
    /**
     * DLT-registered service / transactional / government header (-S / -T / -G). The suffix can be spoofed,
     * so it only exempts messages that carry no link outside the whitelist (see [registeredNoLink]).
     */
    val registeredHeader: Boolean
        get() = sender.suffix == DltSuffix.SERVICE || sender.suffix == DltSuffix.TRANSACTIONAL ||
            sender.suffix == DltSuffix.GOVERNMENT

    /**
     * Registered (-S/-T/-G) header whose message has no link, or only whitelisted links: exempt from the
     * unlisted-bank wording rule (call-back "SMS BLOCK to 9xxxxxxxxx") and the masked-card rule. A
     * non-whitelisted link from such a header still flags (a spoofed suffix gains nothing).
     */
    val registeredNoLink: Boolean
        get() = registeredHeader && links.all { LinkExtractor.isWhitelisted(it) }
}

/**
 * Fraud / scam detection (spec 04b §5) as an ordered rule list; the first hit wins.
 *
 * [TENTATIVE] The original's exact index sets are not recoverable (04b §5.3, §16.1); every rule below
 * is a design reading of the signal families, built from the 05a fraud lists (scmcase1/2/5/6,
 * devnagari 3/4, case21) plus authored vocabulary. Rule ids follow 04b §3.3 where the spec names
 * one; authored rules use ids from [Reason].
 */
class FraudRules(lists: KeywordLists = KeywordLists.EMPTY) {

    // ---- evasive spelling (rows 1–2): scmcase1 / scmcase6 case-insensitive, scmcase2 case-sensitive
    private val evasiveCi: Regex? = tokenRegex(
        lists["scmcase1"].filterIndexed { i, _ -> i != 43 && i != 44 } + lists["scmcase6"], ignoreCase = true,
    )
    // scmcase2[28] "lotto" and [29] "bet" are plain English words (gambling is covered by GAMBLING).
    private val evasiveCs: Regex? = tokenRegex(lists["scmcase2"].filterIndexed { i, _ -> i != 28 && i != 29 }, ignoreCase = false)
    private val datingAbbrev: Regex? = lists.item("scmcase5", 36).takeIf { it.isNotBlank() }
        ?.let { runCatching { Regex(it) }.getOrNull() }

    // ---- Devanagari / Gujarati groups (§5.6)
    private val dev3 = lists["devnagarigetNewfraud03"].filter { it.isNotBlank() }
    private val dev4 = lists["devnagarigetNewfraud04"].filter { it.isNotBlank() }
    private val devWellness = devRegex(dev3.filterIndexed { i, _ -> i !in 17..19 })
    private val devDating = devRegex(dev3.filterIndexed { i, _ -> i in 17..19 })
    private val devGambling = devRegex(dev4.filterIndexed { i, _ -> i in setOf(6, 7, 9, 10) })
    private val devLoanPrize = devRegex(dev4.filterIndexed { i, _ -> i in setOf(0, 1, 2, 3, 4, 5, 8) })

    /** Known bank / brand / government header ids (05a getStringsender, getStringsenderFORID, CASENO14[12,63]). */
    val knownHeaders: Set<String> = buildSet {
        lists["getStringsender"].forEach { if (it.isNotBlank()) add(it.trim().uppercase()) }
        lists["getStringsenderFORID"].forEach { if (it.isNotBlank()) add(it.trim().uppercase()) }
        listOf(12, 63).forEach { i -> lists.item("CASENO14", i).split('|').forEach { if (it.isNotBlank()) add(it.uppercase()) } }
    }

    /** Bank header ids (05a getStringsender). */
    val bankHeaders: Set<String> = lists["getStringsender"].filter { it.isNotBlank() }.map { it.trim().uppercase() }.toSet()

    private val content = ContentRules(lists)

    // ------------------------------------------------------------------ business / header path

    /**
     * Fraud cascade for a business header (`V.f`, spec 04b §5.2–5.3). Returns null when nothing fired.
     * Never fires for digits-only senders of 8+ characters (§5.1).
     */
    fun businessFraud(c: FraudContext): FraudHit? {
        val id = c.sender.canonicalId
        if (id.length >= 8 && id.all { it.isDigit() }) return null
        val t = c.text
        val linked = c.links.filterNot { LinkExtractor.isWhitelisted(it) }
        val hasLink = linked.isNotEmpty()
        val lure = LURE.containsMatchIn(t)
        val sixDigit = c.sender.isNumericHeader && id.length == 6

        // 1–2 Evasive spelling / mixed-case look-alikes (known bank / brand headers are exempt).
        if (!c.knownSender && isEvasive(t)) return FraudHit(FraudLevel.SCAM, Reason.EVASIVE_SPELLING)
        // 3 Transaction wording from a non-bank sender with a link or call-back number (DLT -S/-T/-G exempt).
        if (!c.knownSender && !c.registeredNoLink && !sixDigit && content.isBankTransactionWording(t) && (hasLink || CALLBACK.containsMatchIn(t))) {
            return FraudHit(FraudLevel.SCAM, Reason.TXN_FROM_NON_BANK)
        }
        // 4 Trading tips / guaranteed profit / betting.
        if (!c.knownSender) tradingHit(t)?.let { return FraudHit(FraudLevel.FINANCIAL_RISK, it) }
        // 5 Links: suspicious shape + lure, shortener + lure, short message + link + lure.
        if (!c.knownSender && hasLink && lure) {
            if (linked.any { LinkExtractor.isSuspiciousShape(it) }) return FraudHit(FraudLevel.SCAM, Reason.LINK_SUSPICIOUS_SHAPE)
            if (linked.any { LinkExtractor.isShortener(it) }) return FraudHit(FraudLevel.SCAM, Reason.LINK_SHORTENER_LURE)
            if (c.fullText.length < SHORT_MESSAGE) return FraudHit(FraudLevel.SCAM, Reason.LINK_SHORT_LURE)
        }
        // 6 Six-digit numeric header with bank-transaction wording.
        if (sixDigit && content.isBankTransactionWording(t)) return FraudHit(FraudLevel.MISLEADING, Reason.SIX_DIGIT_BANK_TEXT)
        // 7 Amount with an asterisk / "credited*" fine print (known bank / brand headers exempt).
        if (!c.knownSender && FINE_PRINT.containsMatchIn(t)) return FraudHit(FraudLevel.MISLEADING, Reason.FINE_PRINT_CREDIT)
        // 8 Quack health / sexual wellness / miracle cure (known bank / brand headers exempt: "premature
        // withdrawal of FD", insurers' "premature death").
        if (!c.knownSender && isHealthClaim(t)) return FraudHit(FraudLevel.FAKE, Reason.HEALTH_CLAIM)
        // 9 Amount + fee / penalty wording + link.
        if (!c.knownSender && hasLink && content.hasAmount(t) && FEE.containsMatchIn(t)) {
            return FraudHit(FraudLevel.SCAM, Reason.FEE_LURE)
        }
        // 10 Short message with link and credited / gift / reward wording.
        if (!c.knownSender && hasLink && c.fullText.length < SHORT_MESSAGE && REWARD.containsMatchIn(t) &&
            CLAIM_ACTION.containsMatchIn(t)
        ) {
            return FraudHit(FraudLevel.SCAM, Reason.SHORT_REWARD_LINK)
        }
        // 11–12 Suspicious: odd host, dating abbreviations, call-back lure, masked card + link (raised to
        // scam when an amount is also present), styled Unicode.
        if (!c.knownSender) {
            if (hasLink && !c.registeredNoLink && MASKED_CARD.containsMatchIn(t)) {
                return if (content.hasAmount(t)) FraudHit(FraudLevel.SCAM, Reason.MASKED_CARD_LINK)
                else FraudHit(FraudLevel.SUSPICIOUS, Reason.MASKED_CARD_LINK)
            }
            if (linked.any { LinkExtractor.isOddHost(it) }) return FraudHit(FraudLevel.SUSPICIOUS, Reason.ODD_HOST)
            if (isDating(t)) return FraudHit(FraudLevel.SUSPICIOUS, Reason.DATING)
            if (lure && CALLBACK.containsMatchIn(t)) return FraudHit(FraudLevel.SUSPICIOUS, Reason.CALLBACK_LURE)
            if (c.styledChars >= STYLED_MIN) return FraudHit(FraudLevel.SUSPICIOUS, Reason.STYLED_TEXT)
        }
        return null
    }

    /**
     * Devanagari / Gujarati pass, "SenderID" mode (`p.a`, spec 04b §5.6). Null when nothing fired.
     *
     * Known bank / brand headers (and -G government headers, which count as known) are exempt (a Hindi SBI
     * alert about a "लोन" account is not spam); a bare -S/-T suffix is not, since it can be spoofed. On other headers the loan / prize group needs two signals: two distinct group
     * words, or one plus a link, a Latin lure or a Hindi lure word (तुरंत, मुफ्त, इनाम…). A single "लोन" in
     * an alert is not enough.
     */
    fun devanagariSenderId(text: String, hasLink: Boolean, trusted: Boolean = false): FraudHit? {
        if (trusted) return null
        return when {
            devGambling?.containsMatchIn(text) == true -> FraudHit(FraudLevel.FINANCIAL_RISK, Reason.DEV_GAMBLING)
            hasLink && devWellness?.containsMatchIn(text) == true -> FraudHit(FraudLevel.FAKE, Reason.DEV_WELLNESS)
            devLoanPrizeSignals(text, hasLink) >= 2 -> FraudHit(FraudLevel.NONE, Reason.DEV_LOAN_PRIZE)
            else -> null
        }
    }

    /** Loan / prize signals of [text]: 0 when no group word matched, else distinct words + lure signals. */
    private fun devLoanPrizeSignals(text: String, hasLink: Boolean): Int {
        val words = devLoanPrize?.findAll(text)?.map { it.value }?.toSet()?.size ?: 0
        if (words == 0) return 0
        return words + (if (hasLink) 1 else 0) + (if (LURE.containsMatchIn(text)) 1 else 0) +
            (if (DEV_LURE.containsMatchIn(text)) 1 else 0)
    }

    // ------------------------------------------------------------------ phone-number path

    /** Fake-bank check (spec 04b §4.3 step 2c): bank-transaction text from a personal mobile number. */
    fun fakeBank(text: String): FraudHit? =
        if (content.isBankTransactionWording(text)) FraudHit(FraudLevel.MISLEADING, Reason.FAKE_BANK_FROM_MOBILE) else null

    /** Phone-number variant ("FakeDClass", spec 04b §5.4), unknown mobile senders only. */
    fun mobileFraud(c: FraudContext): FraudHit? {
        val t = c.text
        val hasLink = c.links.any { !LinkExtractor.isWhitelisted(it) }
        if (isEvasive(t)) return FraudHit(FraudLevel.SCAM, Reason.MOBILE_EVASIVE)
        if (hasLink && (LURE.containsMatchIn(t) || MONEY.containsMatchIn(t))) return FraudHit(FraudLevel.SCAM, Reason.MOBILE_LINK_LURE)
        tradingHit(t)?.let { return FraudHit(FraudLevel.FINANCIAL_RISK, Reason.MOBILE_TRADING) }
        if (isDating(t)) return FraudHit(FraudLevel.MISLEADING, Reason.MOBILE_DATING)
        if (isHealthClaim(t)) return FraudHit(FraudLevel.FAKE, Reason.MOBILE_HEALTH)
        return null
    }

    /** Devanagari pass, "Mobile" mode (spec 04b §5.6). */
    fun devanagariMobile(text: String): FraudHit? = when {
        devDating?.containsMatchIn(text) == true -> FraudHit(FraudLevel.MISLEADING, Reason.DEV_DATING)
        devWellness?.containsMatchIn(text) == true -> FraudHit(FraudLevel.FAKE, Reason.DEV_WELLNESS)
        devGambling?.containsMatchIn(text) == true -> FraudHit(FraudLevel.FINANCIAL_RISK, Reason.DEV_GAMBLING)
        devLoanPrize?.containsMatchIn(text) == true -> FraudHit(FraudLevel.NONE, Reason.DEV_LOAN_PRIZE)
        else -> null
    }

    // ------------------------------------------------------------------ signal helpers

    /** Evasive digit-for-letter / I-for-l spellings (scmcase1/2/6) or mixed-case look-alikes (scmcase5 idea). */
    fun isEvasive(text: String): Boolean =
        evasiveCi?.containsMatchIn(text) == true || evasiveCs?.containsMatchIn(text) == true || hasMixedCaseLookAlike(text)

    private fun hasMixedCaseLookAlike(text: String): Boolean = WORD.findAll(text).any { m ->
        val w = m.value
        w.lowercase() in LOOK_ALIKE_TARGETS && w !in BRAND_CASINGS && w != w.lowercase() && w != w.uppercase() &&
            !(w[0].isUpperCase() && w.drop(1) == w.drop(1).lowercase())
    }

    private fun tradingHit(t: String): Int? {
        if (GUARANTEED.containsMatchIn(t)) return Reason.GUARANTEED_PROFIT
        if (GAMBLING.containsMatchIn(t)) return Reason.GAMBLING
        val core = TRADING_CORE.findAll(t).map { it.value.lowercase() }.toSet()
        val extra = TRADING_EXTRA.findAll(t).map { it.value.lowercase() }.toSet()
        return if (core.isNotEmpty() && core.size + extra.size >= 2) Reason.TRADING_TIPS else null
    }

    private fun isHealthClaim(t: String): Boolean {
        if (HEALTH_STRONG.containsMatchIn(t)) return true
        val sexual = HEALTH_SEXUAL.findAll(t).map { it.value.lowercase() }.toSet().size +
            (if (HEALTH_SEXUAL_ABBR.containsMatchIn(t)) 1 else 0) +
            (if (devWellness?.containsMatchIn(t) == true) 1 else 0)
        return sexual >= 2 || (sexual >= 1 && HEALTH_CLAIM_WORDS.containsMatchIn(t))
    }

    private fun isDating(t: String): Boolean =
        DATING.containsMatchIn(t) || datingAbbrev?.containsMatchIn(t) == true

    companion object {
        /** Messages shorter than this count as "short" (spec 04b §5.3 row 10: < 161 chars). */
        const val SHORT_MESSAGE = 161
        /** Minimum styled / look-alike characters for the styled-text signal. */
        const val STYLED_MIN = 3

        private val opt = RegexOption.IGNORE_CASE
        private val WORD = Regex("[A-Za-z]{3,}")
        private val LOOK_ALIKE_TARGETS = setOf("bill", "click", "link", "sbi", "upi", "kyc", "verify", "update", "apply", "paytm")
        /** Legitimate brand casings of the targets (not evasive). */
        private val BRAND_CASINGS = setOf("PayTM", "PayTm", "PAYtm", "eBill", "eKyc", "UPi")

        /** Builds `(?<![\p{L}\p{N}])(?:tok1|tok2…)` with a strict right boundary for short tokens. */
        internal fun tokenRegex(tokens: List<String>, ignoreCase: Boolean): Regex? {
            val parts = tokens.filter { it.isNotBlank() }.mapNotNull { tok ->
                if (runCatching { Regex(tok) }.isFailure) return@mapNotNull null
                val plain = tok.count { it.isLetterOrDigit() }
                "(?:$tok)" + if (plain <= 4) "(?![\\p{L}\\p{N}])" else ""
            }
            if (parts.isEmpty()) return null
            val body = "(?<![\\p{L}\\p{N}])(?:" + parts.joinToString("|") + ")"
            return if (ignoreCase) Regex(body, opt) else Regex(body)
        }

        /** Devanagari tokens with Latin padding allowed around them. */
        private fun devRegex(tokens: List<String>): Regex? =
            tokens.takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|") { t -> Regex.escape(t) }) }

        /** Prize / claim / KYC / job / account-block / challan lures (authored; strong signals only). */
        val LURE = Regex(
            "\\b(prize|winner|won|lottery|lucky draw|jackpot|claim\\w*|unclaimed|" +
                "kyc|pan\\W+(card\\W+)?(update|link|block\\w*)|aadhaa?r\\W+(update|link)|" +
                "(account|a/c|card|wallet|sim|number)\\W+(will\\W+be\\W+|is\\W+|has\\W+been\\W+)?" +
                "(blocked|suspended|deactivated|closed|freezed|frozen)|" +
                "(electricity|power|connection)\\W+(\\w+\\W+){0,3}?(disconnect\\w*|cut)|" +
                "work from home|part[- ]?time job|daily income|earn\\W+(rs|inr|₹)?\\W*\\d+\\W*(per|/|a)\\W*(day|daily|hour)|" +
                "loan\\W+(approved|sanctioned)|instant loan|customs|challan|traffic violation|" +
                "verify now|update now|expir\\w* today)\\b",
            opt,
        )
        private val MONEY = Regex("\\b(loan|cash|money|credit|rs|inr|₹|lakh|crore|salary|income|bonus|deposit)\\b|₹", opt)
        val CALLBACK = Regex(
            "\\b(call|contact|whatsapp|dial|sms|msg|ring)\\b\\W+(\\w+\\W+){0,3}?(\\+?91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}\\b",
            opt,
        )
        private val GUARANTEED = Regex(
            "\\b(guaranteed|assured|fixed|sure[- ]?shot|100\\s*%)\\W+(\\w+\\W+)?(profit|returns?|income|gains?)\\b|" +
                "\\bdouble (your )?(money|investment)\\b",
            opt,
        )
        private val GAMBLING = Regex(
            "\\b(rummy|teen\\s*patti|poker|matka|satta|casino|betting|jackpot|slots?|aviator|bet\\s+(now|on)|" +
                "(welcome|deposit|joining)\\s+bonus|free\\s+spins?)\\b",
            opt,
        )
        private val TRADING_CORE = Regex(
            "\\b(stocks?|stok|sheyar|share\\s*market|intraday|trading|trader|forex|crypto|bitcoin|nifty|" +
                "bank\\s*nifty|sensex|multibagger|f&o|options?\\s+trading)\\b",
            opt,
        )
        private val TRADING_EXTRA = Regex(
            "\\b(tips?|target|stop\\s*loss|profit|returns?|vip|telegram|accuracy|jackpot|" +
                "(whatsapp|telegram|trading|vip)\\s+group|premium\\s+(calls|group))\\b",
            opt,
        )
        private val FINE_PRINT = Regex(
            "((?:\\brs\\.?|\\binr|₹)\\s*[\\d,]*\\d(?:\\.\\d+)?\\s*\\*)|\\b(credited|added|approved)\\s*\\*", opt,
        )
        // "premature" alone is ordinary banking / insurance wording ("premature withdrawal of FD",
        // "premature closure", "premature death"): only "premature ejaculation / discharge" is strong.
        private val HEALTH_STRONG = Regex(
            "\\b(premature\\s+(ejaculation|discharge)|ejaculation|erectile|sex(ual)?\\s+(power|problem|stamina|timing|weakness)|" +
                "s(h)?(e|i){1,2}gh?ra\\s*patan|gupt\\s*rog|mardana\\s*(kamzori|taqat|shakti)|ling\\s+(vardhak|bada))\\b",
            opt,
        )
        private val HEALTH_SEXUAL = Regex(
            "\\b(stamina|kamzori|weakness|men'?s\\s+health|sex\\w*|ling)\\b", opt,
        )
        private val HEALTH_SEXUAL_ABBR = Regex("\\b(ED|PE)\\b")
        private val HEALTH_CLAIM_WORDS = Regex(
            "\\b(miracle|100\\s*%\\s*(cure|result)|permanent\\s+(cure|solution|ilaj|ilaaj)|without\\s+side\\s+effects?|" +
                "herbal|ayurved\\w*|desi\\s+(nuskha|ilaj)|guaranteed\\s+(cure|result))\\b",
            opt,
        )
        private val DATING = Regex(
            "\\b(friendship\\s+(club|service)|dating|lonely|call\\s*girls?|escorts?|hot\\s+(girls|ladies|bhabhi)|" +
                "meet\\s+(girls|singles|ladies)|chat\\s+with\\s+(girls|ladies|bhabhi)|private\\s+chat|" +
                "girl\\s*friend\\s+(service|chahiye))\\b",
            opt,
        )
        private val FEE = Regex(
            "\\b(fee|fees|charges?|penalty|fine|processing|registration\\s+amount|customs|delivery\\s+charge|duty)\\b",
            opt,
        )
        private val CLAIM_ACTION = Regex("\\b(claim|collect|redeem|withdraw|receive|activate)\\w*\\b", opt)
        private val REWARD = Regex("\\b(credited|gift|reward\\w*|cashback|bonus|won|prize)\\b", opt)
        /** Hindi lure words that make a Devanagari loan / prize word a scam signal (authored). */
        private val DEV_LURE = Regex("तुरंत|तुरन्त|मुफ्त|मुफ़्त|इनाम|लॉटरी|लाटरी|क्लिक|बिना\\s*गारंटी|बिना\\s*कागज")
        private val MASKED_CARD = Regex("(?i)(?<![a-z0-9])(x{2,}|\\*{2,})\\d{3,4}\\b")
    }
}

/**
 * Debug rule ids (spec 04b §3.3). Ids named by the spec keep their numbers; [MessageClassifier] uses
 * the remaining ones for its own stages.
 */
object Reason {
    const val NONE = 0
    // fraud cascade, business path (15–28, 366)
    const val EVASIVE_SPELLING = 15
    const val TXN_FROM_NON_BANK = 16
    const val TRADING_TIPS = 17
    const val GUARANTEED_PROFIT = 18
    const val GAMBLING = 19
    const val LINK_SUSPICIOUS_SHAPE = 22
    const val LINK_SHORT_LURE = 23
    const val LINK_SHORTENER_LURE = 24
    const val SIX_DIGIT_BANK_TEXT = 25
    const val SHORT_REWARD_LINK = 26
    const val FINE_PRINT_CREDIT = 27
    const val HEALTH_CLAIM = 28
    const val FEE_LURE = 366
    // suspicious (class 3) rows have no id in the original; authored ids:
    const val MASKED_CARD_LINK = 367
    const val ODD_HOST = 368
    const val DATING = 369
    const val CALLBACK_LURE = 370
    const val STYLED_TEXT = 371
    // promo cascade (29–57)
    const val PROMO_DLT_SUFFIX = 29
    const val PROMO_VOCABULARY = 30
    // numeric-header rules (84–93)
    const val NUMERIC_TRANSACTION = 86
    const val NUMERIC_OTP_IN_TXN = 87
    const val NUMERIC_BALANCE = 88
    const val NUMERIC_SMART_ALERT = 89
    const val NUMERIC_DELIVERY = 90
    const val NUMERIC_GAMING_LINK = 91
    const val NUMERIC_GAMING_WWW = 92
    const val SIX_DIGIT_GAMING = 93
    // phone-number path (283–300, 355–363)
    const val INDIAN_SHORT_NUMBER = 293
    const val FAKE_BANK_FROM_MOBILE = 294
    const val MOBILE_EVASIVE = 295
    const val MOBILE_LINK_LURE = 296
    const val MOBILE_TRADING = 297
    const val MOBILE_DATING = 298
    const val MOBILE_HEALTH = 299
    const val DEV_WELLNESS = 355
    const val DEV_DATING = 356
    const val DEV_GAMBLING = 357
    const val DEV_LOAN_PRIZE = 358
    /** E-mail style sender on a blocked domain list (case18[76..77]). */
    const val EMAIL_BLOCKED_DOMAIN = 444
    // category stages (authored)
    const val OTP = 400
    const val OTP_PAYMENT = 401
    const val TRANSACTION = 402
    const val BALANCE = 403
    const val SMART_ALERT = 404
    const val DELIVERY = 405
    const val SERVICE_FALLBACK = 406
    const val IMPORTANT_FALLBACK = 407
    const val KNOWN_CONTACT = 408
}
