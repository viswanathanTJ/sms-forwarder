package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.TestKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAnalyzerTest {
    private val analyzer = MessageAnalyzer(TestKeywords.lists)
    private fun a(sender: String, body: String, kind: SenderKind = SenderKind.BUSINESS) = analyzer.analyze(sender, body, kind)

    @Test fun otp() {
        val r = a("AD-AMAZON", "482913 is your Amazon OTP. Do not share it.")
        assertEquals(MessageType.OTP, r.type)
        assertEquals("482913", r.otp!!.code)
    }

    @Test fun paymentOtp_isOtpOnly() {
        val r = a("VM-HDFCBK", "OTP 551234 for txn of Rs 2,000.00 at FLIPKART on HDFC Bank card XX1234")
        assertEquals(MessageType.OTP, r.type)
        assertNull(r.txn)
    }

    @Test fun debit() {
        val r = a("VM-HDFCBK", "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank")
        assertEquals(MessageType.TRANSACTION, r.type)
        assertEquals(1250.0, r.txn!!.amount, 0.001)
        assertEquals("HDFC Bank", r.senderName)
    }

    @Test fun missedCallWins() {
        val r = a("JM-AIRTEL", "You have a missed call from +919962055555 at 10:11")
        assertEquals(MessageType.MISSED_CALL, r.type)
        assertNotNull(r.missedCall)
    }

    @Test fun promo_isSpam() = assertEquals(MessageType.SPAM, a("BX-MYNTRA-P", "FLAT 70% OFF! End of season sale is LIVE. Shop now bit.ly/x").type)

    @Test fun contactChat_isPersonal() = assertEquals(MessageType.PERSONAL, a("+919840012345", "Lunch ready, come home by 1", SenderKind.CONTACT).type)

    @Test fun unknownNumberChat_isPersonal() = assertEquals(MessageType.PERSONAL, a("+919840012345", "Hi, are you free tomorrow?", SenderKind.UNKNOWN_NUMBER).type)

    @Test fun businessFallback_isImportant() {
        assertEquals(MessageType.IMPORTANT, a("VM-HDFCBK", "Your cheque book request has been received.").type)
    }

    @Test fun delivery() = assertEquals(
        MessageType.DELIVERY,
        a("AD-AMAZON", "Your Amazon order has been shipped and will be out for delivery today. Track at amzn.in/d/abc").type,
    )

    @Test fun noKeywords_stillWorks() {
        val m = MessageAnalyzer(null)
        val r = m.analyze("JM-AIRTEL", "You have a missed call from +919962055555", SenderKind.BUSINESS)
        assertEquals(MessageType.MISSED_CALL, r.type)
        assertFalse(r.classifierAvailable)
        assertEquals(MessageType.IMPORTANT, m.analyze("VM-HDFCBK", "Rs 100 debited", SenderKind.BUSINESS).type)
        assertEquals(MessageType.PERSONAL, m.analyze("+919840012345", "hello", SenderKind.UNVERIFIED).type)
    }
}
