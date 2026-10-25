// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/MessageClassifier.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import com.viswa2k.smsforwarder.classify.FraudLevel
import com.viswa2k.smsforwarder.classify.MessageCategory

/** One message to classify. */
data class ClassifierInput(
    /** Raw sender address. */
    val sender: String,
    val body: String,
    /** Sender is a saved contact or a number the user has sent to (`ML.h`, spec 04b §4.1). */
    val isKnown: Boolean = false,
)

/** User settings that change classification (spec 04b §4.5, §9). */
data class ClassifierOptions(
    /** "Auto filter Spam": content rules may move messages to the Promotional store. */
    val autoFilterSpam: Boolean = true,
    /** "Show scam warnings": evaluate fraud rules even when filtering is off (flag without moving). */
    val fraudWarnings: Boolean = true,
)

/** Classifier output (the original's `j` result: category, reason, fraud class; spec 04b §4.1). */
data class Classification(
    val category: MessageCategory,
    val fraudLevel: FraudLevel = FraudLevel.NONE,
    /** Debug rule id ([Reason]). */
    val reason: Int = Reason.NONE,
    /** Extracted OTP when [category] is an OTP category. */
    val otp: OtpResult? = null,
    val sender: SenderInfo,
    /** Links found in the (folded) body. */
    val links: List<Link> = emptyList(),
    /** Body contained styled Unicode / look-alike letters (the original logged reason 999). */
    val styledText: Boolean = false,
) {
    /** Goes to the Promotional store. */
    val inSpamStore: Boolean get() = category.inSpamStore
}

/**
 * The classification engine (spec 04b §4): routes a message by sender kind, runs the ordered stages and
 * returns category, fraud class, reason and OTP. Pure Kotlin and thread-safe; construct once (regexes
 * are compiled in the constructor) and share.
 *
 * User Allow/Block rules (spec 04a §3.3: allow → block → block-unknown → classifier) are applied by
 * the block stage (Task 8) around this classifier; nothing here reads them.
 */
class MessageClassifier(lists: KeywordLists = KeywordLists.EMPTY) {

    val otpExtractor = OtpExtractor(lists)
    val fraudRules = FraudRules(lists)
    val contentRules = ContentRules(lists)

    /** case18[76..77]: e-mail sender domains treated as plain spam (reason 444). */
    private val blockedEmailDomains: List<String> =
        lists.items("case18", 76..77).map { it.lowercase() }.ifEmpty { listOf("rbm", "rcs") }

    fun classify(input: ClassifierInput, options: ClassifierOptions = ClassifierOptions()): Classification {
        val sender = SenderIds.parse(input.sender)
        val folded = TextFolding.fold(input.body)
        val styledChars = changedChars(input.body)
        val links = LinkExtractor.extract(folded)
        val text = LinkExtractor.blank(folded, links)
        val base = Classification(
            category = MessageCategory.IMPORTANT, sender = sender, links = links, styledText = styledChars > 0,
        )
        val ctx = FraudContext(
            sender = sender, text = text, fullText = folded, links = links, styledChars = styledChars,
            knownSender = isKnownHeader(sender),
        )
        return if (sender.isBusiness || sender.type == SenderType.EMAIL) {
            business(input, options, base, ctx)
        } else {
            phone(input, options, base, ctx)
        }
    }

    // ------------------------------------------------------------------ business / header path (r.b / r.c)

    private fun business(input: ClassifierInput, o: ClassifierOptions, base: Classification, c: FraudContext): Classification {
        val filter = o.autoFilterSpam
        val t = c.text
        val hasLink = c.links.isNotEmpty()

        // §5.2 step 2: e-mail sender on a blocked domain: plain spam.
        if (c.sender.type == SenderType.EMAIL && filter) {
            val domain = c.sender.canonicalId.substringAfter('@')
            if (blockedEmailDomains.any { d -> domain.split('.').any { it == d } }) {
                return base.copy(category = MessageCategory.PROMOTIONAL, reason = Reason.EMAIL_BLOCKED_DOMAIN)
            }
        }
        // Stage 5: fraud cascade. Filter on: block + class. Filter off, warnings on: flag only.
        var flagged: FraudHit? = null
        if (filter || o.fraudWarnings) {
            fraudRules.businessFraud(c)?.let { hit ->
                if (filter) return spam(base, hit)
                flagged = hit
            }
        }
        val flaggedBase = flagged?.let { base.copy(fraudLevel = it.level) } ?: base
        val numeric = c.sender.isNumericHeader

        fun otpClassification(otp: OtpResult): Classification {
            val cat = if (otp.isPayment) MessageCategory.OTP_TRANSACTION else MessageCategory.OTP
            val reason = when {
                otp.isPayment && numeric -> Reason.NUMERIC_OTP_IN_TXN
                otp.isPayment -> Reason.OTP_PAYMENT
                else -> Reason.OTP
            }
            return flaggedBase.copy(category = cat, reason = reason, otp = otp)
        }

        // Stages 6–7: alerts / transactions (numeric headers use reasons 86–90). A transaction that also
        // carries a real OTP (affirmative "… is 123456" phrase, not a "do not share your OTP" warning)
        // is upgraded to 34.
        if (contentRules.isTransaction(t)) {
            val otp = otpExtractor.extract(input.body, input.sender)
            return if (otp != null) {
                flaggedBase.copy(
                    category = MessageCategory.OTP_TRANSACTION, otp = otp,
                    reason = if (numeric) Reason.NUMERIC_OTP_IN_TXN else Reason.OTP_PAYMENT,
                )
            } else {
                flaggedBase.copy(category = MessageCategory.TRANSACTION, reason = if (numeric) Reason.NUMERIC_TRANSACTION else Reason.TRANSACTION)
            }
        }
        // Stage 8 (early): a message that names an OTP explicitly and yields a code is an OTP even when it
        // also reads like a recharge / bill / travel / gaming alert ("OTP for your recharge … expires in
        // 5 min", "1234 is your OTP for web check-in").
        val strongOtp = otpExtractor.hasStrongKeyword(input.body)
        val earlyOtp = if (strongOtp) otpExtractor.extract(input.body, input.sender) else null
        if (earlyOtp != null) return otpClassification(earlyOtp)

        // Stage 3: Devanagari / regional-script pass (content blocking; only with the filter on). Runs after
        // the transaction / OTP checks; known bank / brand / government headers are exempt.
        if (filter) fraudRules.devanagariSenderId(t, hasLink, trusted = c.knownSender)?.let { return spam(base, it) }

        if (contentRules.isBalanceAlert(t)) {
            return flaggedBase.copy(category = MessageCategory.BALANCE_ALERT, reason = if (numeric) Reason.NUMERIC_BALANCE else Reason.BALANCE)
        }
        if (contentRules.isSmartAlert(t)) {
            return flaggedBase.copy(category = MessageCategory.SMART_ALERT, reason = if (numeric) Reason.NUMERIC_SMART_ALERT else Reason.SMART_ALERT)
        }
        // Stage 7: promotional-marker rules for numeric headers (gaming vocabulary + link / www).
        if (filter && numeric && contentRules.isGamingPromo(t, hasLink)) {
            return if (c.sender.canonicalId.length == 6) {
                base.copy(category = MessageCategory.PROMOTIONAL, fraudLevel = FraudLevel.MISLEADING, reason = Reason.SIX_DIGIT_GAMING)
            } else {
                base.copy(category = MessageCategory.PROMOTIONAL, reason = if (hasLink) Reason.NUMERIC_GAMING_LINK else Reason.NUMERIC_GAMING_WWW)
            }
        }
        // Stage 8: OTP (generic "code" gate; the explicit-OTP case was handled above).
        if (!strongOtp) otpExtractor.extract(input.body, input.sender)?.let { return otpClassification(it) }
        // Stage 8: delivery status.
        if (contentRules.isDelivery(t)) {
            return flaggedBase.copy(category = MessageCategory.DELIVERY, reason = if (numeric) Reason.NUMERIC_DELIVERY else Reason.DELIVERY)
        }
        // Promotional rules [interpretation]: DLT "-P" header, or promotional wording.
        if (filter) {
            if (c.sender.suffix == DltSuffix.PROMOTIONAL) {
                return base.copy(category = MessageCategory.PROMOTIONAL, reason = Reason.PROMO_DLT_SUFFIX)
            }
            // Only undeclared headers: -S / -T / -G are registered service / transactional / government.
            if (c.sender.suffix == DltSuffix.NONE && contentRules.promoScore(t) >= ContentRules.PROMO_THRESHOLD) {
                return base.copy(category = MessageCategory.PROMOTIONAL, reason = Reason.PROMO_VOCABULARY)
            }
        }
        // Stage 9: fallback.
        return if (c.sender.isServiceStyle) {
            flaggedBase.copy(category = MessageCategory.SERVICE, reason = Reason.SERVICE_FALLBACK)
        } else {
            flaggedBase.copy(category = MessageCategory.IMPORTANT, reason = Reason.IMPORTANT_FALLBACK)
        }
    }

    // ------------------------------------------------------------------ phone-number path (q.a / q.c)

    private fun phone(input: ClassifierInput, o: ClassifierOptions, base: Classification, c: FraudContext): Classification {
        // A personal number's "gate code is 4521" / "wifi password is …" is not an OTP. Indian short numbers
        // (operator / service codes) use the full gate; other phone numbers need explicit OTP wording, or, when
        // the sender is not a contact / sent-to number, a service cue ("Your WhatsApp code: 123-456",
        // "123 456 is your Instagram code"). Known (personal) senders always need explicit OTP wording.
        val otpGate = SenderIds.isIndianShortNumber(input.sender) || otpExtractor.hasStrongKeyword(input.body) ||
            (!input.isKnown && otpExtractor.hasServiceCodeCue(input.body))
        val otp = if (otpGate) {
            otpExtractor.extract(input.body, input.sender)
        } else null
        val otpResult = otp?.let { base.copy(category = MessageCategory.OTP, reason = Reason.OTP, otp = it) }

        // 1. Known contact / sent-to number: parsers only, never blocked for content.
        if (input.isKnown) return otpResult ?: base.copy(category = MessageCategory.IMPORTANT, reason = Reason.KNOWN_CONTACT)

        // 2b. Indian-looking short number (+91 + 6–8 digits): important / OTP.
        if (SenderIds.isIndianShortNumber(input.sender)) {
            return otpResult ?: base.copy(category = MessageCategory.IMPORTANT, reason = Reason.INDIAN_SHORT_NUMBER)
        }
        val filter = o.autoFilterSpam
        var flagged: FraudHit? = null
        if (filter || o.fraudWarnings) {
            // 2c. Fake bank: transaction text from a personal mobile number. 2d/2e: phone-variant rules.
            val dev = fraudRules.devanagariMobile(c.text)
            val hit = fraudRules.fakeBank(c.text) ?: fraudRules.mobileFraud(c) ?: dev?.takeIf { it.level != FraudLevel.NONE }
            if (hit != null) {
                if (filter) return spam(base, hit)
                flagged = hit
            } else if (filter && dev != null) {
                // Devanagari loan/prize group: block without a class (content blocking, filter only).
                return spam(base, dev)
            }
        }

        val flaggedBase = flagged?.let { base.copy(fraudLevel = it.level) } ?: base
        if (otpResult != null) return otpResult.copy(fraudLevel = flaggedBase.fraudLevel)
        return flaggedBase.copy(category = MessageCategory.IMPORTANT, reason = Reason.IMPORTANT_FALLBACK)
    }

    // ------------------------------------------------------------------ helpers

    private fun spam(base: Classification, hit: FraudHit) =
        base.copy(category = MessageCategory.PROMOTIONAL, fraudLevel = hit.level, reason = hit.reason)

    private fun isKnownHeader(sender: SenderInfo): Boolean =
        sender.suffix == DltSuffix.GOVERNMENT ||
            (sender.type == SenderType.ALPHANUMERIC && sender.canonicalId in fraudRules.knownHeaders)

    /** Non-ASCII letters / digits that fold to plain ASCII ones (styled Unicode, homoglyphs). */
    private fun changedChars(original: String): Int {
        var n = 0
        var i = 0
        while (i < original.length) {
            val cp = original.codePointAt(i)
            val len = Character.charCount(cp)
            if (cp > 0x7F && Character.isLetterOrDigit(cp)) {
                val f = TextFolding.fold(original.substring(i, i + len))
                if (f.isNotEmpty() && f.all { it.code < 0x80 && it.isLetterOrDigit() }) n++
            }
            i += len
        }
        return n
    }
}
