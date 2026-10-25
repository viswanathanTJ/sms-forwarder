// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/LinkExtractorTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {

    private fun hosts(text: String) = LinkExtractor.extract(text).map { it.host }

    @Test fun `extracts scheme, www and bare links`() {
        assertEquals(listOf("example.com"), hosts("Visit www.example.com/abc."))
        assertEquals(listOf("example.in"), hosts("Pay at https://example.in/pay?id=1, thanks"))
        assertEquals(listOf("bit.ly"), hosts("Details: bit.ly/3abcDE"))
        assertEquals(listOf("shop.example.co"), hosts("Go to shop.example.co/sale now"))
        assertEquals(listOf("10.20.30.40"), hosts("Open http://10.20.30.40/x"))
    }

    @Test fun `amounts, abbreviations and missing spaces are not links`() {
        assertEquals(emptyList<String>(), hosts("Rs.500 paid. Thank you.In case of query call us"))
        assertEquals(emptyList<String>(), hosts("A/c no.1234 debited, bal 5.00"))
        assertEquals(emptyList<String>(), hosts("e.g. see the note"))
    }

    @Test fun `shape checks and whitelist`() {
        fun link(t: String) = LinkExtractor.extract(t).single()
        assertTrue(LinkExtractor.isSuspiciousShape(link("http://192.168.1.10/a")))
        assertTrue(LinkExtractor.isSuspiciousShape(link("http://claim-now.xyz/a")))
        assertTrue(LinkExtractor.isSuspiciousShape(link("http://pay88812.example.com/a")))
        assertTrue(LinkExtractor.isSuspiciousShape(link("http://averyveryverylonglabelname.example.com")))
        assertFalse(LinkExtractor.isSuspiciousShape(link("https://example.com/a")))
        assertFalse(LinkExtractor.isSuspiciousShape(link("https://wa.me/919876543210")))
        assertTrue(LinkExtractor.isWhitelisted(link("https://portal.incometax.gov.in/x")))
        assertTrue(LinkExtractor.isShortener(link("https://bit.ly/abc")))
        assertTrue(LinkExtractor.isOddHost(link("http://prize.top/x")))
        assertFalse(LinkExtractor.isOddHost(link("http://example.com/x")))
    }

    @Test fun `blanking removes link text`() {
        val text = "Claim at http://x.example.xyz/a now"
        val blanked = LinkExtractor.blank(text, LinkExtractor.extract(text))
        assertFalse(blanked.contains("example"))
        assertTrue(blanked.contains("Claim at"))
    }
}
