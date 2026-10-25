// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/OtpExtractorTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpExtractorTest {

    private val otp = TestKeywords.classifier.otpExtractor
    private val builtIn = OtpExtractor() // fallback vocabulary without the asset

    @Test fun `extracts codes in many formats`() {
        val cases = listOf(
            "123456 is your OTP for login to Example app. Do not share it with anyone." to "123456",
            "Your OTP is 4821. Valid for 10 minutes." to "4821",
            "Use 739201 as your one time password to verify your account." to "739201",
            "Your verification code is 90817" to "90817",
            "<#> 6612 is your login code. Ab3dE9fGh1k" to "6612",
            "OTP: 77665544" to "77665544",
            "Enter OTP 3920 to verify your number. For help call 1800 258 6161" to "3920",
            "Login OTP 552211 sent to +91 98765 43210 on 12/03/2024 at 10:30" to "552211",
            "Your Apple ID code is: 482917. Do not share it." to "482917",
            "Your Example code is 482 917. Don't share this code with others" to "482917",
            "WhatsApp code 482-917. You can also tap on this link to verify your phone" to "482917",
            "Dear user, the OTP to reset your password is 8823 and is valid for 5 mins" to "8823",
            "Never share your OTP. OTP for your request is 640912. -Example Bank" to "640912",
            "Your one-time password: 3344 (valid 3 min)" to "3344",
            "Your delivery code is 4521. Share it with the delivery agent only." to "4521",
            "123 is your OTP" to "123",
            "ABCXYZ is your OTP for sign in" to "ABCXYZ",
            "OTP for payment of Rs 1,499.00 at Example Store on card XX1234 is 583920. Valid till 10:45" to "583920",
        )
        for ((body, code) in cases) {
            assertEquals(body, code, otp.extract(body)?.code)
        }
    }

    @Test fun `amounts phone numbers dates and offers are not codes`() {
        val negatives = listOf(
            "Rs 5000 debited from a/c XX1234 on 12-03-24. Avl bal Rs 10,000",
            "Use code SAVE20 for 20% off on orders above Rs 499",
            "Your pincode 560001 is serviceable for delivery",
            "Call 9876543210 for the code",
            "Your order 4521 has been shipped",
            "OTP will be sent to your number 9876543210",
            "Your OTP for payment of Rs 1500 will be sent shortly",
            "Use OTP and pay INR 2,000.50 by 25 Mar 2024",
            "Error code 5023 occurred, please retry",
            "Coupon code 7788 gives you flat cashback on your next order",
            "Meeting at 10:30 tomorrow, code of conduct attached",
            // Bank alerts with a security warning: reference ids are not codes.
            "Rs 500 debited from A/c XX1234 on 12-Jan. UPI Ref 412345678. Do not share your OTP with anyone.",
            "Rs.500 spent on card ending 1234 at SHOPX. Txn ID 98765432. Don't share OTP/PIN with anyone.",
            "Your a/c XX9911 is credited with INR 2000. Ref 556677. Never share your password or OTP.",
            "Your order 123456 is confirmed. Delivery code will be sent before delivery.",
            "",
        )
        for (body in negatives) assertNull(body, otp.extract(body))
    }

    @Test fun `payment OTP carries the amount`() {
        val r = otp.extract("OTP for txn of INR 2,499.50 at SHOPX on card ending 1234 is 583920. Do not share.")!!
        assertEquals("583920", r.code)
        assertEquals("2499.50", r.paymentAmount)
        assertTrue(r.isPayment)
        val plain = otp.extract("482913 is your OTP to login")!!
        assertFalse(plain.isPayment)
    }

    @Test fun `purpose label and display format`() {
        assertEquals("OTP to login", otp.extract("482913 is your OTP to login")?.purpose)
        assertEquals("OTP to reset", otp.extract("Use 482913 as OTP to reset your PIN. Valid 5 min.")?.purpose)
        assertEquals("OTP", otp.extract("Your OTP is 482913")?.purpose)
        assertEquals("1 2 3 4 5 6", OtpResult("123456", "otp", "OTP").displayCode)
        assertEquals("A B 1 2", OtpExtractor.spaced("AB12"))
    }

    @Test fun `special senders`() {
        assertEquals(
            "AB12CD",
            otp.extract("OTP for filing GSTR is EVC is AB12CD valid for 30 min", "AD-GSTIND")?.code,
        )
        assertEquals(
            "A1B2C3D4",
            otp.extract("Your OTP to authenticate the request is A1B2C3D4 and expires soon.")?.code,
        )
        assertEquals(
            "4821",
            otp.extract("Please share this code with the agent after order: 4821 for delivery", "VM-SWIGGY")?.code,
        )
    }

    @Test fun `built-in vocabulary works without the asset`() {
        assertEquals("482913", builtIn.extract("Your verification code is 482913")?.code)
        assertNull(builtIn.extract("Flat 40% off, use code 4400 on your next order"))
    }
}
