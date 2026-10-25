// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/MessageClassifierTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import com.viswa2k.smsforwarder.classify.FraudLevel
import com.viswa2k.smsforwarder.classify.MessageCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven classifier tests. Bodies are made up and describe abstract patterns (they are not real
 * scam templates).
 */
class MessageClassifierTest {

    private val classifier = TestKeywords.classifier
    private val on = ClassifierOptions(autoFilterSpam = true, fraudWarnings = true)
    private val offWarn = ClassifierOptions(autoFilterSpam = false, fraudWarnings = true)
    private val offSilent = ClassifierOptions(autoFilterSpam = false, fraudWarnings = false)

    private data class Case(
        val sender: String,
        val body: String,
        val category: MessageCategory,
        val fraud: FraudLevel = FraudLevel.NONE,
        val known: Boolean = false,
        val reason: Int? = null,
    )

    private fun check(cases: List<Case>, options: ClassifierOptions = on) {
        for (c in cases) {
            val r = classifier.classify(ClassifierInput(c.sender, c.body, c.known), options)
            val label = "${c.sender}: ${c.body} -> ${r.category}/${r.fraudLevel}/${r.reason}"
            assertEquals(label, c.category, r.category)
            assertEquals(label, c.fraud, r.fraudLevel)
            c.reason?.let { assertEquals(label, it, r.reason) }
        }
    }

    @Test fun `important business categories`() = check(
        listOf(
            Case("AX-EXMPLB", "482913 is your OTP to login. Do not share.", MessageCategory.OTP, reason = Reason.OTP),
            Case(
                "VM-EXMPLB-S", "OTP for txn of Rs 2,499.00 at SHOPX on card XX1234 is 583920. Do not share.",
                MessageCategory.OTP_TRANSACTION, reason = Reason.OTP_PAYMENT,
            ),
            Case(
                "AX-HDFCBK", "Rs 1,250.00 debited from A/c XX4321 on 05-Jun via UPI ref 412345678901. Not you? Call 18002586161",
                MessageCategory.TRANSACTION,
            ),
            Case("JM-EXWALT", "INR 300 credited to your wallet for order 1182. Balance INR 900.", MessageCategory.TRANSACTION),
            Case("AX-EXMPLB", "Avl bal in A/c XX1234 is Rs 5,000.00 as on 01-Jun.", MessageCategory.BALANCE_ALERT),
            Case("VK-POWERC", "Your electricity bill of Rs 840 is due on 15-06-2024.", MessageCategory.SMART_ALERT),
            Case("TX-RAILWY", "PNR 4512345678 confirmed. Coach B2 berth 34. Departure 06:10.", MessageCategory.SMART_ALERT),
            Case("BZ-SHOPCO", "Your order #A12 has been shipped and will be delivered by tomorrow.", MessageCategory.DELIVERY),
            Case("AD-650025", "Your request has been registered.", MessageCategory.SERVICE, reason = Reason.SERVICE_FALLBACK),
            Case("123456", "Your request has been registered.", MessageCategory.SERVICE),
            Case("VM-CLINIC", "Thank you for visiting. We value your feedback.", MessageCategory.IMPORTANT),
        ),
    )

    @Test fun `bank alerts with security warnings are transactions, not OTPs`() = check(
        listOf(
            Case("AX-HDFCBK", "Rs 500 debited from A/c XX1234 on 12-Jan. UPI Ref 412345678. Do not share your OTP with anyone.", MessageCategory.TRANSACTION),
            Case("AX-EXMPLB", "Rs.500 spent on card ending 1234 at SHOPX. Txn ID 98765432. Don't share OTP/PIN with anyone.", MessageCategory.TRANSACTION),
            Case("AX-EXMPLB", "Your a/c XX9911 is credited with INR 2000. Ref 556677. Never share your password or OTP.", MessageCategory.TRANSACTION),
            Case("BZ-SHOPCO", "Your order 123456 is confirmed. Delivery code will be sent before delivery.", MessageCategory.DELIVERY),
        ),
    )

    @Test fun `plain words and brand casings are not evasive`() = check(
        listOf(
            Case("+919876512345", "I bet you'll like it", MessageCategory.IMPORTANT),
            Case("AX-QWERTY", "I bet you will love the new menu at our cafe", MessageCategory.IMPORTANT),
            Case("AX-QWERTY", "Pay your electricity bill via PayTM before the due date", MessageCategory.SMART_ALERT),
            Case("AX-QWERTY", "Lotto results are out on our website", MessageCategory.IMPORTANT),
            // Known brand headers are exempt from the evasive-spelling rule.
            Case("JM-AMAZON", "Your B0NUS points are ready", MessageCategory.IMPORTANT),
            // A bare 10-digit mobile starting with 91 is a phone number: fraud checks still run.
            Case("9123456789", "Rs 500 credited to your account XX12", MessageCategory.PROMOTIONAL, FraudLevel.MISLEADING),
        ),
    )

    @Test fun `promotional versus transactional`() = check(
        listOf(
            Case("JD-SHOPCO-P", "New styles have landed in store.", MessageCategory.PROMOTIONAL, reason = Reason.PROMO_DLT_SUFFIX),
            Case(
                "BZ-SHOPCO", "Flat 50% off on all shoes! Shop now, limited period offer. T&C apply.",
                MessageCategory.PROMOTIONAL, reason = Reason.PROMO_VOCABULARY,
            ),
            Case("BZ-SHOPCO", "Your order #A12 has been delivered. Rate us!", MessageCategory.DELIVERY),
            // -P header still delivers an OTP / transaction as important.
            Case("JD-SHOPCO-P", "123456 is your OTP to sign in", MessageCategory.OTP),
            // A single offer word is not enough.
            Case("BZ-SHOPCO", "Your membership offer letter is attached in the app.", MessageCategory.IMPORTANT),
            // Government / service / transactional headers never become promotional by wording.
            Case("CP-GOVTIN-G", "Free vaccination camp this Sunday, limited period, hurry.", MessageCategory.IMPORTANT),
            Case("VM-SHOPCO-T", "Flat 50% off on all shoes! Shop now, limited period offer.", MessageCategory.IMPORTANT),
            Case("VM-SHOPCO-S", "Flat 50% off on all shoes! Shop now, limited period offer.", MessageCategory.IMPORTANT),
        ),
    )

    @Test fun `fraud signals per class on header senders`() = check(
        listOf(
            Case("AX-QWERTY", "Your l0an of Rs 50000 is ready, apply n0w", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.EVASIVE_SPELLING),
            Case("AX-QWERTY", "Your account needs attention, cLiCk to update details", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.EVASIVE_SPELLING),
            Case(
                "AX-QWERTY", "Rs 4999 credited to your account XX12. To receive call 9876512345",
                MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.TXN_FROM_NON_BANK,
            ),
            Case("AX-QWERTY", "Join our VIP group for daily stock tips with high accuracy", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK, reason = Reason.TRADING_TIPS),
            Case("AX-QWERTY", "Guaranteed profit every week on your investment", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK, reason = Reason.GUARANTEED_PROFIT),
            Case("AX-QWERTY", "Play rummy today and grab a welcome bonus", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK, reason = Reason.GAMBLING),
            Case("AX-QWERTY", "You are the lucky draw winner! Collect at http://get-gift.xyz/abc", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.LINK_SUSPICIOUS_SHAPE),
            Case("AX-QWERTY", "KYC pending, update now: https://bit.ly/abc12", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.LINK_SHORTENER_LURE),
            Case("AX-QWERTY", "Your electricity connection will be disconnected tonight. See https://power-help.example.com", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.LINK_SHORT_LURE),
            Case("561234", "Rs 2000 debited from your account XX1234 today.", MessageCategory.PROMOTIONAL, FraudLevel.MISLEADING, reason = Reason.SIX_DIGIT_BANK_TEXT),
            Case("AX-QWERTY", "Loan of Rs 5,00,000* credited* to your profile, pre-approved", MessageCategory.PROMOTIONAL, FraudLevel.MISLEADING, reason = Reason.FINE_PRINT_CREDIT),
            Case("AX-QWERTY", "Herbal remedy for premature ejaculation, consult today", MessageCategory.PROMOTIONAL, FraudLevel.FAKE, reason = Reason.HEALTH_CLAIM),
            Case("AX-QWERTY", "Pay Rs 99 processing fee to release your funds http://fastfunds.example.com/p", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.FEE_LURE),
            Case("AX-QWERTY", "Rs 750 cashback credited! Redeem at http://myshop.example.com/r", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.SHORT_REWARD_LINK),
            Case("AX-QWERTY", "Statement for card XX1234 ready: http://stmt.example.com/s", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.MASKED_CARD_LINK),
            Case("AX-QWERTY", "See details at http://10.11.12.13/info", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.ODD_HOST),
            Case("AX-QWERTY", "Lonely tonight? Meet singles near you", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.DATING),
            Case("AX-QWERTY", "Job: work from home and earn more. WhatsApp 9876512345", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.CALLBACK_LURE),
            Case("AX-QWERTY", "𝐅𝐑𝐄𝐄 surprise waiting for you", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.STYLED_TEXT),
            Case("promo@rbm.example", "Hello there", MessageCategory.PROMOTIONAL, FraudLevel.NONE, reason = Reason.EMAIL_BLOCKED_DOMAIN),
        ),
    )

    @Test fun `ordinary messages are not flagged`() = check(
        listOf(
            Case("AX-ZOOMCR", "Your booking is confirmed. Pickup at 10 AM.", MessageCategory.SMART_ALERT),
            Case("VM-MYGYMS", "Your membership renews on 12 Jul. Call 9876543210 for queries.", MessageCategory.IMPORTANT),
            Case("AD-SCHOOL", "Dear parent, school will remain closed tomorrow.", MessageCategory.IMPORTANT),
            Case("BZ-CLINIC", "Your appointment is confirmed for 3 PM. Clinic timings 9 to 5.", MessageCategory.SMART_ALERT),
            Case("BZ-NEWSLT", "Read our monthly newsletter at https://news.example.com/june", MessageCategory.IMPORTANT),
            Case("+919876512345", "Bro, check this https://example.com/photos", MessageCategory.IMPORTANT),
            Case("+919876512345", "Can you pay me Rs 200 for lunch?", MessageCategory.IMPORTANT),
            Case("+919876512345", "Send me the stock report when free", MessageCategory.IMPORTANT),
            Case("+919876512345", "Thank you… see you soon…", MessageCategory.IMPORTANT),
        ),
    )

    @Test fun `known bank and brand headers are exempt from link rules`() = check(
        listOf(
            Case("AX-HDFCBK", "Rs 500 debited from A/c XX1234. View at https://example-bank.example.in/s", MessageCategory.TRANSACTION),
            Case("JM-AMAZON", "Your refund of Rs 299 is processed to your card XX9876. Details: https://amzn.in/x", MessageCategory.TRANSACTION),
            Case("CP-ABCDEF-G", "Track your application at http://1.2.3.4/track", MessageCategory.IMPORTANT),
        ),
    )

    @Test fun `devanagari and regional script pass`() = check(
        listOf(
            Case("AX-QWERTY", "रमी खेलो और जीतो", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK, reason = Reason.DEV_GAMBLING),
            Case("AX-QWERTY", "तुरंत लोन पाएं", MessageCategory.PROMOTIONAL, FraudLevel.NONE, reason = Reason.DEV_LOAN_PRIZE),
            Case("AX-QWERTY", "लोन चाहिए? अभी आवेदन करें https://loanz.example.com/a", MessageCategory.PROMOTIONAL, FraudLevel.NONE, reason = Reason.DEV_LOAN_PRIZE),
            Case("+919876512345", "नए दोस्त से चैट करें", MessageCategory.PROMOTIONAL, FraudLevel.MISLEADING, reason = Reason.DEV_DATING),
            Case("+919876512345", "आयुर्वेद इलाज", MessageCategory.PROMOTIONAL, FraudLevel.FAKE, reason = Reason.DEV_WELLNESS),
            Case("+919876512345", "नमस्ते, कैसे हो?", MessageCategory.IMPORTANT),
        ),
    )

    @Test fun `phone number path`() = check(
        listOf(
            Case("+919876512345", "Rs 500 credited to your account XX12", MessageCategory.IMPORTANT, known = true, reason = Reason.KNOWN_CONTACT),
            Case("+919876512345", "Rs 500 credited to your account XX12", MessageCategory.PROMOTIONAL, FraudLevel.MISLEADING, reason = Reason.FAKE_BANK_FROM_MOBILE),
            Case("+919876512345", "hi, are we meeting at 5?", MessageCategory.IMPORTANT),
            Case("+919876512345", "You won a prize, claim at http://bit.ly/x1", MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.MOBILE_LINK_LURE),
            Case("+919876512345", "Earn daily from stock tips, join our trading group", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK),
            Case("+919876512345", "Your OTP is 482913", MessageCategory.OTP),
            Case("+919876512345", "Your OTP is 482913", MessageCategory.OTP, known = true),
            Case("+91650025", "Your complaint 1234 is resolved", MessageCategory.IMPORTANT, reason = Reason.INDIAN_SHORT_NUMBER),
            // Known contacts are never content-blocked, whatever they write.
            Case("+919876512345", "Play rummy with me tonight, guaranteed profit", MessageCategory.IMPORTANT, known = true),
        ),
    )

    @Test fun `auto filter off with warnings flags without moving`() = check(
        listOf(
            Case("AX-QWERTY", "Your l0an of Rs 50000 is ready, apply n0w", MessageCategory.IMPORTANT, FraudLevel.SCAM),
            Case("JD-SHOPCO-P", "New styles have landed in store.", MessageCategory.IMPORTANT),
            Case("BZ-SHOPCO", "Flat 50% off on all shoes! Shop now, limited period offer.", MessageCategory.IMPORTANT),
            Case("+919876512345", "Rs 500 credited to your account XX12", MessageCategory.IMPORTANT, FraudLevel.MISLEADING),
            Case("AX-QWERTY", "रमी खेलो और जीतो", MessageCategory.IMPORTANT),
            Case("AX-EXMPLB", "482913 is your OTP to login.", MessageCategory.OTP),
            Case("AX-HDFCBK", "Rs 1,250.00 debited from A/c XX4321 via UPI.", MessageCategory.TRANSACTION),
            Case("promo@rbm.example", "Hello there", MessageCategory.IMPORTANT),
        ),
        offWarn,
    )

    @Test fun `auto filter and warnings off run parsers only`() = check(
        listOf(
            Case("AX-QWERTY", "Your l0an of Rs 50000 is ready, apply n0w", MessageCategory.IMPORTANT),
            Case("+919876512345", "Rs 500 credited to your account XX12", MessageCategory.IMPORTANT),
            Case("AX-EXMPLB", "482913 is your OTP to login.", MessageCategory.OTP),
            Case("561234", "Rs 2000 debited from your account XX1234 today.", MessageCategory.TRANSACTION),
        ),
        offSilent,
    )

    @Test fun `classification exposes sender, links and otp`() {
        val r = classifier.classify(ClassifierInput("VM-EXMPLB-T", "Your OTP is 482913. Visit example.com", false))
        assertEquals(MessageCategory.OTP, r.category)
        assertEquals("482913", r.otp?.code)
        assertEquals(DltSuffix.TRANSACTIONAL, r.sender.suffix)
        assertEquals(listOf("example.com"), r.links.map { it.host })
        assertFalse(r.inSpamStore)
        assertTrue(classifier.classify(ClassifierInput("JD-SHOPCO-P", "x")).inSpamStore)
    }

    @Test fun `empty and unknown senders do not crash`() {
        assertEquals(MessageCategory.IMPORTANT, classifier.classify(ClassifierInput("", "hello")).category)
        assertEquals(MessageCategory.IMPORTANT, classifier.classify(ClassifierInput("+919876512345", "")).category)
        // Built-in fallback (no asset) still classifies.
        val bare = MessageClassifier()
        assertEquals(MessageCategory.OTP, bare.classify(ClassifierInput("AX-EXMPLB", "Your OTP is 482913")).category)
    }

    // ------------------------------------------------------------------ final-review B fixes (fix-2)

    @Test fun `B-I2 hindi bank and brand alerts are not devanagari spam`() = check(
        listOf(
            // Known bank headers: a single "लोन" (loan) in an EMI / loan-account alert stays important.
            Case("AX-SBIINB", "आपके लोन खाते XX1234 से EMI की राशि 05-06 को काटी जाएगी।", MessageCategory.IMPORTANT),
            Case("VM-HDFCBK", "प्रिय ग्राहक, आपके लोन खाते की EMI देय है। विवरण: https://hdfcbank.com/x", MessageCategory.IMPORTANT),
            // Transaction / OTP detection runs before the Devanagari pass.
            Case("AX-SBIINB", "Rs 5,000 credited to A/c XX1234 (लोन खाता). Ref 12345678", MessageCategory.TRANSACTION),
            Case("AX-QWERTY", "Rs 2,000 debited from A/c XX9876 towards लोन EMI. Ref 4455667788", MessageCategory.TRANSACTION),
            Case("AX-QWERTY", "482913 is your OTP for लोन application. Do not share.", MessageCategory.OTP),
            // -G (government) headers count as known and are exempt; a bare -S/-T suffix is not (it can be spoofed),
            // but a single loan word still needs a second signal.
            Case("CP-AYUSHM-G", "आयुर्वेद इलाज शिविर, विवरण https://ayush.gov.in/c", MessageCategory.IMPORTANT),
            Case("VM-NBFCLN-S", "आपका लोन स्वीकृत हो गया है, किस्त 10 तारीख को", MessageCategory.IMPORTANT),
            Case("VM-HOSPTL-T", "मुफ्त रोग जांच शिविर, पंजीकरण https://hospital.example.com/r", MessageCategory.PROMOTIONAL, FraudLevel.FAKE, reason = Reason.DEV_WELLNESS),
            Case("VM-NBFCLN-T", "मुफ्त लोन, तुरंत पाएं", MessageCategory.PROMOTIONAL, FraudLevel.NONE, reason = Reason.DEV_LOAN_PRIZE),
            // Unknown undeclared header: one loan word alone is not enough; two signals still are.
            Case("AX-QWERTY", "आपके लोन खाते की किस्त 10 तारीख को देय है", MessageCategory.IMPORTANT),
            Case("AX-QWERTY", "मुफ्त लोन, तुरंत पाएं", MessageCategory.PROMOTIONAL, FraudLevel.NONE, reason = Reason.DEV_LOAN_PRIZE),
            Case("AX-QWERTY", "रमी खेलो और जीतो", MessageCategory.PROMOTIONAL, FraudLevel.FINANCIAL_RISK, reason = Reason.DEV_GAMBLING),
        ),
    )

    @Test fun `B-I3 premature in deposit and insurance alerts is not a health claim`() = check(
        listOf(
            Case(
                "AX-HDFCBK", "Premature withdrawal of FD XX123 processed. Rs 50,000 credited to a/c XX1234.",
                MessageCategory.TRANSACTION,
            ),
            Case("AX-QWERTY", "Premature closure of your fixed deposit 1234 is complete. Contact branch.", MessageCategory.IMPORTANT),
            Case("VM-INSURE", "Claim on premature death under policy 99887 is under review.", MessageCategory.IMPORTANT),
            Case("+919876512345", "Premature withdrawal from RD done", MessageCategory.IMPORTANT),
            // Real quack-health spam still flags.
            Case("AX-QWERTY", "Herbal remedy for premature ejaculation, consult today", MessageCategory.PROMOTIONAL, FraudLevel.FAKE, reason = Reason.HEALTH_CLAIM),
            Case("AX-QWERTY", "Sex power capsules, 100% cure, herbal", MessageCategory.PROMOTIONAL, FraudLevel.FAKE, reason = Reason.HEALTH_CLAIM),
            // Known bank headers are exempt from the health rule altogether.
            Case("AX-HDFCBK", "Health insurance for sexual weakness? No. Your premium is due on 12 Jul.", MessageCategory.SMART_ALERT),
        ),
    )

    @Test fun `B-I4 explicit OTPs beat smart-alert and balance shortcuts`() {
        val cases = listOf(
            "AX-JIOINF" to ("OTP for your recharge of Rs 299 is 4821. Expires in 5 min" to "4821"),
            "VM-INDIGO" to ("1234 is your OTP for web check-in" to "1234"),
            "AX-AIRIND" to ("Your OTP for web check-in of PNR ABC123 is 482913. Valid for 10 mins." to "482913"),
            "VK-IRCTCI" to ("OTP for PNR 4512345678 booking is 739201" to "739201"),
            "AX-LICIND" to ("OTP to pay LIC premium is 123456, expires in 10 mins" to "123456"),
            "AX-EXMPLB" to ("Your verification code is 552190. Avl bal enquiry requested." to "552190"),
            "561234" to ("Play games on our App: OTP 664422 for login. www.example.com" to "664422"),
        )
        for ((sender, pair) in cases) {
            val (body, code) = pair
            val r = classifier.classify(ClassifierInput(sender, body), on)
            assertTrue("$sender: $body -> ${r.category}", r.category.isOtp)
            assertEquals("$sender: $body", code, r.otp?.code)
        }
        // Without an OTP the alerts keep their category.
        check(
            listOf(
                Case("AX-JIOINF", "Your recharge of Rs 299 expires tomorrow. Recharge now.", MessageCategory.SMART_ALERT),
                Case("VM-INDIGO", "Web check-in is open for flight 6E 123, PNR ABC123.", MessageCategory.SMART_ALERT),
                Case("AX-EXMPLB", "Avl bal in A/c XX1234 is Rs 5,000.00 as on 01-Jun. Never share your OTP.", MessageCategory.BALANCE_ALERT),
                Case("VK-POWERC", "Your electricity bill of Rs 840 is due on 15-06-2024. Do not share OTP with anyone.", MessageCategory.SMART_ALERT),
            ),
        )
    }

    @Test fun `B-I5 personal texts with a code are not OTPs`() = check(
        listOf(
            Case("+919876512345", "the gate code is 4521", MessageCategory.IMPORTANT, known = true, reason = Reason.KNOWN_CONTACT),
            Case("+919876512345", "wifi password is 12345678", MessageCategory.IMPORTANT, known = true, reason = Reason.KNOWN_CONTACT),
            Case("+919876512345", "Locker pin code is 2580, don't tell anyone", MessageCategory.IMPORTANT, known = true),
            Case("+919876512345", "the gate code is 4521", MessageCategory.IMPORTANT),
            // Explicit OTP wording from a phone number is still an OTP (known or not).
            Case("+919876512345", "Your OTP is 482913", MessageCategory.OTP, known = true),
            Case("+919876512345", "G-482913 is your Google verification code.", MessageCategory.OTP),
            Case("+919876512345", "Your one time password is 7788", MessageCategory.OTP),
            // N2: service codes from (non-contact) long numbers: brand words or affirmative "code is" phrases.
            Case("+14155550123", "Your WhatsApp code: 123-456", MessageCategory.OTP),
            Case("+919876512345", "Your Apple ID Code is: 123456. Don't share it with anyone.", MessageCategory.OTP),
            Case("+919876512345", "Your Uber code is 1234", MessageCategory.OTP),
            Case("+919876512345", "123 456 is your Instagram code. Don't share it.", MessageCategory.OTP),
            // ...but contacts / sent-to numbers still need explicit OTP wording.
            Case("+919876512345", "Your Uber code is 1234", MessageCategory.IMPORTANT, known = true),
            Case("+919876512345", "wifi password is 12345678", MessageCategory.IMPORTANT),
            // Indian short numbers (operator / service codes) keep the full OTP gate.
            Case("+91650025", "Your access code is 482913", MessageCategory.OTP),
            // Business headers keep the full gate.
            Case("AX-EXMPLB", "Your access code is 482913", MessageCategory.OTP),
        ),
    )

    @Test fun `B-minor10 registered DLT headers are exempt from unlisted-bank rules only without a link`() = check(
        listOf(
            Case("VM-NEWBNK-T", "Rs 500 debited from A/c XX1234. Not you? SMS BLOCK to 9876512345", MessageCategory.TRANSACTION),
            Case("VM-NEWBNK-S", "Statement for card XX1234 generated. Call 9876512345 for queries", MessageCategory.SMART_ALERT),
            // N1: a spoofed -S/-T suffix with a non-bank link still flags.
            Case(
                "AX-HDFCBX-S", "Rs 4,999 debited from A/c XX1234. Not you? Dispute at http://hdfc-dispute.example.com/d",
                MessageCategory.PROMOTIONAL, FraudLevel.SCAM, reason = Reason.TXN_FROM_NON_BANK,
            ),
            Case("VM-NEWBNK-S", "Statement for card XX1234 ready: http://stmt.example.com/s", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.MASKED_CARD_LINK),
            // A whitelisted link keeps the exemption; a genuine listed bank with its own domain is not flagged.
            Case("VM-NEWBNK-T", "Rs 500 debited from A/c XX1234 via UPI. Help: https://www.npci.org.in/x", MessageCategory.TRANSACTION),
            Case("AX-HDFCBK", "Rs 4,999 debited from A/c XX1234. Not you? Visit https://www.hdfcbank.com/dispute", MessageCategory.TRANSACTION),
            // Undeclared headers still flag.
            Case("AX-QWERTY", "Statement for card XX1234 ready: http://stmt.example.com/s", MessageCategory.PROMOTIONAL, FraudLevel.SUSPICIOUS, reason = Reason.MASKED_CARD_LINK),
        ),
    )

    @Test fun `strong otp keyword detection`() {
        val x = classifier.otpExtractor
        listOf("Your OTP is 1", "one-time password", "verification code", "Use 2FA code", "<#> 1234 is your code", "Login code: 99")
            .forEach { assertTrue(it, x.hasStrongKeyword(it)) }
        listOf("gate code is 4521", "wifi password is 12345678", "promo code SAVE10", "pin code 600001", "dotp")
            .forEach { assertFalse(it, x.hasStrongKeyword(it)) }
    }
}
