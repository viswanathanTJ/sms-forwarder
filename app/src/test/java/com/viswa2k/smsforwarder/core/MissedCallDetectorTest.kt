package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MissedCallDetectorTest {
    @Test fun airtel() = assertEquals(MissedCall("+919962055555", "10:11", 1),
        MissedCallDetector.detect("JM-AIRTEL", "You have a missed call from +919962055555 at 10:11 on 25-Oct. -Airtel"))

    @Test fun jio() = assertEquals("9840012345",
        MissedCallDetector.detect("JK-JIOINF", "9840012345 tried to call you on 25-10-2026 at 08:05 PM. You can now call back.")?.caller)

    @Test fun jio_timeKeepsAmPm() = assertEquals("08:05 PM",
        MissedCallDetector.detect("JK-JIOINF", "9840012345 tried to call you on 25-10-2026 at 08:05 PM. You can now call back.")?.time)

    @Test fun vi_count() = assertEquals(2,
        MissedCallDetector.detect("VK-VIALRT", "You have 2 missed calls from 9876543210. Last call at 09:40")?.count)

    @Test fun vi_callerAndTime() = assertEquals(MissedCall("9876543210", "09:40", 2),
        MissedCallDetector.detect("VK-VIALRT", "You have 2 missed calls from 9876543210. Last call at 09:40"))

    @Test fun bsnl() = assertNotNull(MissedCallDetector.detect("BZ-BSNLIN", "Missed call alert: 04423456789 called you at 14:02"))

    @Test fun spacedNumber() = assertEquals("+919962055555",
        MissedCallDetector.detect("JM-AIRTEL", "You have a missed call from +91 99620 55555 at 10:11")?.caller)

    @Test fun triedCalling() = assertEquals("9840012345",
        MissedCallDetector.detect("AX-AIRTEL", "9840012345 tried calling you at 7:30 AM")?.caller)

    @Test fun shortCodeSender() = assertNotNull(
        MissedCallDetector.detect("52222", "Missed call from 9840012345 at 10:11"))

    @Test fun noTime_isNull() = assertNull(
        MissedCallDetector.detect("JK-JIOINF", "You have a missed call from 9840012345")?.time)

    @Test fun cueWithoutNumber_isNot() = assertNull(
        MissedCallDetector.detect("JM-AIRTEL", "Missed call alert service is now active. Dial 121 for help."))

    @Test fun onlySenderDigitsAsNumber_isNot() = assertNull(
        MissedCallDetector.detect("5222222", "Missed call alert from 5222222 service"))

    @Test fun dateIsNotAPhone() = assertNull(
        MissedCallDetector.detect("JM-AIRTEL", "Missed call alert on 25-10-2026 at 10:11"))

    @Test fun otpMentioningCall_isNot() = assertNull(MissedCallDetector.detect("AD-HDFCBK", "Do not share OTP 123456 with anyone, even if they call you"))

    @Test fun personalChat_isNot() = assertNull(MissedCallDetector.detect("+919840012345", "sorry I missed your call, will call later"))

    @Test fun personalNumberWithRealShape_isNot() = assertNull(
        MissedCallDetector.detect("+919840012345", "You have a missed call from 9962055555 at 10:11"))

    @Test fun giveMissedCallInstruction_isNot() = assertNull(
        MissedCallDetector.detect("AD-HDFCBK", "Give a missed call on 9223166666 to know your balance"))

    @Test fun missedCallToNumberInstruction_isNot() = assertNull(
        MissedCallDetector.detect("AD-HDFCBK", "Miss call to 9223166666 for balance enquiry"))

    @Test fun triedCallingWithTollFree_isNot() = assertNull(
        MissedCallDetector.detect("AX-AMAZON", "We tried calling you about your order. Call 18001234567 for help"))

    @Test fun missedCallAlertActivatedTollFree_isNot() = assertNull(
        MissedCallDetector.detect("JM-AIRTEL", "Missed call alert is activated on your number. For help call 18001034444"))

    @Test fun deliveryPartnerCalledYou_isNot() = assertNull(
        MissedCallDetector.detect("AX-BLUEDT", "Delivery partner 9812345678 called you regarding your order"))

    @Test fun otpWithTollFreeCall_isNot() = assertNull(
        MissedCallDetector.detect("AD-HDFCBK", "OTP 123456 for login. If you did not request, call 18001234567 immediately"))

    @Test fun bankWeCalledYou_isNot() = assertNull(
        MissedCallDetector.detect("VM-HDFCBK", "Dear customer, we called you on 9812345678 regarding your card. Please call back"))

    @Test fun numberFollowedBySpacedDigits_notGlued() = assertEquals("9840012345",
        MissedCallDetector.detect("JM-AIRTEL", "You have a missed call from 9840012345 25 Oct at 10:11")?.caller)

    @Test fun fiveFiveGrouping() = assertEquals("9962055555",
        MissedCallDetector.detect("JM-AIRTEL", "You have a missed call from 99620 55555 at 10:11")?.caller)
}
