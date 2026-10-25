package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.FraudLevel
import com.viswa2k.smsforwarder.classify.TestKeywords
import com.viswa2k.smsforwarder.core.Channel.SMS
import com.viswa2k.smsforwarder.core.Channel.TELEGRAM
import com.viswa2k.smsforwarder.core.Style.CUSTOM
import com.viswa2k.smsforwarder.core.Style.ORIGINAL
import com.viswa2k.smsforwarder.core.Style.SMART
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatterTest {
    private val analyzer = MessageAnalyzer(TestKeywords.lists)
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun sms(sender: String, body: String) = IncomingSms(sender, body, 1_000L, 2_000L, null)

    private fun fmt(
        s: IncomingSms,
        style: Style,
        channel: Channel,
        template: String? = null,
        kind: SenderKind = SenderKind.BUSINESS,
        a: Analysis = analyzer.analyze(s.sender, s.body, kind),
    ) = MessageFormatter.format(s, a, style, template, channel, "Pixel 8", zone)

    private val otpSms = sms("AD-AMAZON", "482913 is your Amazon OTP. Do not share it.")
    private val callSms = sms("JM-AIRTEL", "You have a missed call from +919962055555 at 10:11")
    private val debitSms = sms("VM-HDFCBK", "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank")

    @Test fun telegramSmartOtp() = assertEquals(
        "🔐 OTP <code>482913</code>\nAmazon · AD-AMAZON · Pixel 8\n<i>482913 is your Amazon OTP. Do not share it.</i>",
        fmt(otpSms, SMART, TELEGRAM),
    )

    @Test fun telegramSmartDebit() {
        val out = fmt(debitSms, SMART, TELEGRAM)
        assertTrue(out, out.startsWith("💸 Debited ₹1,250"))
        assertTrue(out, out.contains("A/c XX4521 · HDFC Bank"))
        assertTrue(out, out.contains("Balance ₹23,410.55"))
        assertTrue(out, out.contains("Pixel 8 · VM-HDFCBK"))
    }

    @Test fun telegramSmartDebit_merchantLineOnlyWhenParsed() {
        val a = analyzer.analyze(debitSms.sender, debitSms.body, SenderKind.BUSINESS)
        val withMerchant = a.copy(txn = a.txn!!.copy(merchant = "Swiggy", paymentMode = "UPI"))
        assertTrue(fmt(debitSms, SMART, TELEGRAM, a = withMerchant).contains("\nTo Swiggy · UPI\n"))
        val bare = a.copy(txn = a.txn!!.copy(merchant = "", paymentMode = ""))
        assertFalse(fmt(debitSms, SMART, TELEGRAM, a = bare).contains("To "))
    }

    @Test fun telegramSmartMissedCall() = assertEquals(
        "📞 Missed call\n+919962055555 at 10:11\nPixel 8 · via JM-AIRTEL",
        fmt(callSms, SMART, TELEGRAM),
    )

    @Test fun smsSmartOtp() = assertEquals("OTP 482913 - Amazon (Pixel 8)", fmt(otpSms, SMART, SMS))

    @Test fun smsSmartHasNoHtml() {
        val out = fmt(debitSms, SMART, SMS)
        assertFalse(out, out.contains("<") || out.contains("&"))
        assertTrue(out, out.contains("1,250"))
    }

    @Test fun original() {
        assertEquals("<b>AD-AMAZON</b> · Pixel 8\n${otpSms.body}", fmt(otpSms, ORIGINAL, TELEGRAM))
        assertEquals("From AD-AMAZON: ${otpSms.body}", fmt(otpSms, ORIGINAL, SMS))
    }

    @Test fun custom_tokens() {
        val t = "%r · %s\\n%t %o %a\\n%m"
        assertEquals(
            "Pixel 8 · AD-AMAZON\nOTP 482913 \n${otpSms.body}",
            fmt(otpSms, CUSTOM, SMS, t),
        )
        val d = fmt(debitSms, CUSTOM, SMS, "%t %a %o|")
        assertEquals("Transaction ₹1,250 |", d)
    }

    @Test fun custom_doesNotReexpandTokensInValues() {
        val s = sms("AD-AMAZON", "100% sure %s %m")
        assertEquals("100% sure %s %m", fmt(s, CUSTOM, SMS, "%m"))
    }

    @Test fun escapesHtml() {
        val s = sms("AD-AMAZON", "a<b & c>d")
        assertTrue(fmt(s, SMART, TELEGRAM).contains("a&lt;b &amp; c&gt;d"))
        assertTrue(fmt(s, ORIGINAL, TELEGRAM).contains("a&lt;b &amp; c&gt;d"))
        assertTrue(fmt(s, CUSTOM, TELEGRAM, "<%m>").startsWith("&lt;a&lt;b &amp; c&gt;d&gt;"))
        assertEquals("a<b & c>d", fmt(s, ORIGINAL, SMS).removePrefix("From AD-AMAZON: "))
        assertEquals("&lt;&amp;&gt;", MessageFormatter.escapeHtml("<&>"))
    }

    @Test fun longBodyTruncatedAndBalanced() {
        val s = sms("AD-AMAZON", "x".repeat(5000))
        val out = fmt(s, SMART, TELEGRAM)
        assertTrue(out.length <= MessageFormatter.TELEGRAM_MAX)
        assertTrue(out.endsWith("…</i>"))
        assertTrue(out.startsWith("⭐ Other important\nAD-AMAZON · Pixel 8\n<i>"))
    }

    @Test fun longOtpBodyKeepsCodeAndTags() {
        val s = sms("AD-AMAZON", "482913 is your Amazon OTP. " + "y".repeat(5000))
        val out = fmt(s, SMART, TELEGRAM)
        assertTrue(out.length <= 4096)
        assertTrue(out.startsWith("🔐 OTP <code>482913</code>\n"))
        assertTrue(out.endsWith("…</i>"))
    }

    @Test fun truncationNeverSplitsEntity() {
        val s = sms("AD-AMAZON", "&".repeat(5000))
        for (style in listOf(SMART, ORIGINAL)) {
            val out = fmt(s, style, TELEGRAM)
            assertTrue(out.length <= 4096)
            val body = out.substringAfter("\n").substringAfter("\n", out.substringAfter("\n")).removePrefix("<i>").removeSuffix("</i>")
            assertTrue(body.endsWith("&amp;…"))
        }
    }

    @Test fun longCustomTruncated() {
        val out = fmt(sms("AD-AMAZON", "z".repeat(6000)), CUSTOM, TELEGRAM, "%s\n%m")
        assertEquals(4096, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun suspiciousWarning() {
        val s = sms("AD-AMAZON", "Click here to claim your prize")
        val a = analyzer.analyze(s.sender, s.body, SenderKind.BUSINESS).copy(fraud = FraudLevel.SUSPICIOUS)
        assertTrue(fmt(s, SMART, TELEGRAM, a = a).contains("⚠ Suspicious"))
    }

    @Test fun unparsedAmountFallsBackToGeneric() {
        val a = analyzer.analyze(debitSms.sender, debitSms.body, SenderKind.BUSINESS)
        val noAmount = a.copy(txn = a.txn!!.copy(amount = 0.0))
        assertEquals(
            "💸 Transaction\nVM-HDFCBK · Pixel 8\n<i>${MessageFormatter.escapeHtml(debitSms.body)}</i>",
            fmt(debitSms, SMART, TELEGRAM, a = noAmount),
        )
    }

    @Test fun missedCallFallsBackToReceivedTime() {
        val a = analyzer.analyze(callSms.sender, callSms.body, SenderKind.BUSINESS)
        val noTime = a.copy(missedCall = a.missedCall!!.copy(time = null))
        // receivedAt 2000 ms = 05:30:02 IST
        assertEquals("📞 Missed call\n+919962055555 at 05:30\nPixel 8 · via JM-AIRTEL", fmt(callSms, SMART, TELEGRAM, a = noTime))
    }

    // ---- WhatsApp origin ----------------------------------------------------------------------

    private val waOtp = IncomingSms("WA:Amazon", "482913 is your Amazon OTP. Do not share it.", 1_000L, 2_000L, null, Origin.WHATSAPP)
    private fun waA(s: IncomingSms, kind: SenderKind = SenderKind.UNKNOWN_NUMBER) = analyzer.analyze(s.sender, s.body, kind, Origin.WHATSAPP)

    @Test fun whatsApp_telegramSmartOtp() = assertEquals(
        "🔐 OTP <code>482913</code>\nWhatsApp · Amazon · Pixel 8\n<i>482913 is your Amazon OTP. Do not share it.</i>",
        fmt(waOtp, SMART, TELEGRAM, a = waA(waOtp)),
    )

    @Test fun whatsApp_smsSmartOtp() = assertEquals("OTP 482913 - Amazon (Pixel 8)", fmt(waOtp, SMART, SMS, a = waA(waOtp)))

    @Test fun whatsApp_customSenderToken() = assertEquals(
        "WhatsApp: Amazon | 482913",
        fmt(waOtp, CUSTOM, SMS, "%s | %o", a = waA(waOtp)),
    )

    @Test fun whatsApp_original() {
        assertEquals("From WhatsApp · Amazon: 482913 is your Amazon OTP. Do not share it.", fmt(waOtp, ORIGINAL, SMS, a = waA(waOtp)))
        assertTrue(fmt(waOtp, ORIGINAL, TELEGRAM, a = waA(waOtp)).startsWith("<b>WhatsApp · Amazon</b> · Pixel 8\n"))
    }

    @Test fun whatsApp_smartFallbackShowsDisplayName() {
        val s = IncomingSms("WA:Acme <Corp>", "Your cheque book request has been received.", 1_000L, 2_000L, null, Origin.WHATSAPP)
        val out = fmt(s, SMART, TELEGRAM, a = waA(s))
        assertTrue(out, out.contains("WhatsApp · Acme &lt;Corp&gt; · Pixel 8"))
        assertFalse(out, out.contains("WA:"))
    }
}
