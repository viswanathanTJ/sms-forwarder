// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/KeywordListsTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordListsTest {

    private val lists = TestKeywords.lists

    @Test fun `every 05a section is present with its declared size`() {
        val expected = mapOf(
            "getStringACC" to 33, "getStringEXTRA" to 102, "ALLCASE" to 65, "NEWCASES" to 67, "CASENO14" to 66,
            "CASENO15" to 150, "CASENO16" to 64, "getNewCases18" to 81, "getNewCases19" to 15, "getNewCases20" to 90,
            "getNewCases21" to 86, "getNewfraud01" to 77, "getNewfraud02" to 56, "devnagarigetNewfraud03" to 23,
            "devnagarigetNewfraud04" to 11, "getNewfraud05" to 39, "getNewFraud06" to 19, "getNewFraud07" to 1,
            "getStringsender" to 194, "getStringsenderFORID" to 1163, "getStringChangeMerchantCategory4" to 4948,
            "getStringChangeMerchantCategory5" to 5036, "getStringFOROTP" to 60, "getStringOTW" to 16,
            "getStringOSP8" to 8, "getStringChangeMerchantCategoryFood" to 1,
        )
        expected.forEach { (name, size) -> assertEquals(name, size, lists[name].size) }
        assertEquals(137, lists.names.size)
    }

    @Test fun `indices and empty entries are preserved`() {
        assertEquals("accno", lists.item("getStringACC", 0))
        assertEquals("Prepaid", lists.item("getStringACC", 32))
        assertEquals("", lists.item("getStringEXTRA", 101))
        assertEquals("", lists.item("getStringOTW", 15))
        assertEquals("y0n0", lists.item("scmcase1", 0))
        assertEquals("rbm", lists.item("case18", 76))
        assertEquals("rcs", lists.item("case18", 77))
        assertEquals(" A/c Services", lists.item("waBrandHeading", 1))
        assertEquals("", lists.item("scmcase1", 999))
    }

    @Test fun `lists resolve by getter name or pref key`() {
        assertEquals(lists["getNewfraud01"], lists["scmcase1"])
        assertEquals(lists["getStringsenderFORID"], lists["just_sender_id"])
        assertEquals(lists["getStringsenderFORID"], lists["senderID_1000"])
        assertTrue(lists.has("case21"))
        assertFalse(lists.has("n/a"))
        assertTrue(lists["no-such-list"].isEmpty())
    }

    @Test fun `mini json handles escapes and nesting`() {
        val parsed = KeywordLists.parse("""{"version":1,"lists":{"a":["x\"y","क\n",""]},"prefKeys":{"k":"a"}}""")
        assertEquals(listOf("x\"y", "क\n", ""), parsed["k"])
    }
}
