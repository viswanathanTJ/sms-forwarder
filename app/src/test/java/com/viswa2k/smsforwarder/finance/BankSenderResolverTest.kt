// Ported from SMS app: app/src/test/java/com/viswa2k/sms/finance/BankSenderResolverTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.KeywordLists
import com.viswa2k.smsforwarder.classify.TestKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSenderResolverTest {

    private val r = BankSenderResolver(TestKeywords.lists)

    private data class Row(val address: String, val id: String, val name: String, val isBank: Boolean, val source: BankSender.Source)

    @Test fun `lookup chain`() {
        val rows = listOf(
            Row("AX-HDFCBK", "HDFCBK", "HDFC Bank", true, BankSender.Source.BANK_TABLE),
            Row("VM-HDFCBK-S", "HDFCBK", "HDFC Bank", true, BankSender.Source.BANK_TABLE),
            Row("JD-ICICIB-T", "ICICIB", "ICICI Bank", true, BankSender.Source.BANK_TABLE),
            Row("ATMSBI", "ATMSBI", "SBI", true, BankSender.Source.BANK_TABLE),
            Row("BZ-SBIINB", "SBIINB", "SBI", true, BankSender.Source.BANK_TABLE),
            Row("AXAXISBK", "AXISBK", "Axis Bank", true, BankSender.Source.BANK_TABLE),
            Row("AD-PAYTMB", "PAYTMB", "Paytm", false, BankSender.Source.BRAND_TABLE),
            Row("AX-SBIUPI", "SBIUPI", "SBI UPI", true, BankSender.Source.BRAND_TABLE),
            Row("VK-SBYONO", "SBYONO", "SBI YONO", true, BankSender.Source.BRAND_TABLE),
            Row("AX-BOBFIN", "BOBFIN", "BOB Financial", true, BankSender.Source.BRAND_TABLE),
            Row("AX-HDFCSM", "HDFCSM", "HDFC Bank", true, BankSender.Source.HEURISTIC),
            Row("AX-KOTKPB", "KOTKPB", "Kotak Mahindra Bank", true, BankSender.Source.HEURISTIC),
            Row("AX-MYCOBK", "MYCOBK", "MYCOBK", true, BankSender.Source.HEURISTIC),
            Row("AX-ZXQWER", "ZXQWER", "ZXQWER", false, BankSender.Source.UNKNOWN),
        )
        for (row in rows) {
            val s = r.resolve(row.address)
            assertEquals(row.address, row.id, s.id)
            assertEquals(row.address, row.name, s.name)
            assertEquals(row.address, row.isBank, s.isBank)
            assertEquals(row.address, row.source, s.source)
            assertTrue(row.address, s.isBusinessRoute)
            assertFalse(row.address, s.isDigitsOnly)
        }
    }

    @Test fun `phone numbers and digit senders`() {
        val phone = r.resolve("+919876543210")
        assertEquals(BankSender.Source.PHONE, phone.source)
        assertFalse(phone.isBank)
        assertFalse(phone.isBusinessRoute)
        val short = r.resolve("56767")
        assertTrue(short.isDigitsOnly)
        assertTrue(short.isBusinessRoute)
        val mobile = r.resolve("9876543210")
        assertFalse(mobile.isBusinessRoute)
    }

    @Test fun `logos and bank key`() {
        assertEquals("logo_hdfc", r.resolve("AX-HDFCBK").logo)
        assertEquals("logo_sbi", r.resolve("AX-SBIINB").logo)
        assertEquals("sbi", BankSender.normalizeBankName("SBI YONO"))
        assertEquals("sbi", BankSender.normalizeBankName("SBI UPI"))
        assertEquals("hdfc bank", r.resolve("AX-HDFCBK").bankKey)
    }

    @Test fun `degrades without the keyword asset`() {
        val bare = BankSenderResolver(KeywordLists.EMPTY)
        assertEquals("HDFC Bank", bare.resolve("AX-HDFCBK").name)
        assertEquals("XYZABC", bare.resolve("AX-XYZABC").name)
        // The parser still works on built-in fallbacks.
        val p = TransactionParser(KeywordLists.EMPTY)
        val t = p.parse("AX-HDFCBK", "Rs 500.00 debited from your a/c XX1234 on 01-01-24. Avl bal Rs 1,000.00")
        assertEquals(ParseStatus.OK, t.status)
        assertEquals(500.0, t.amount, 0.0)
    }
}
