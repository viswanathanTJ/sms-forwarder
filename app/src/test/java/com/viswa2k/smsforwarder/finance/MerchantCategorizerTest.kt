// Ported from SMS app: app/src/test/java/com/viswa2k/sms/finance/MerchantCategorizerTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.TestKeywords
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantCategorizerTest {

    private val c = MerchantCategorizer(TestKeywords.lists)

    private fun cat(merchant: String, body: String = "", amount: Double = 100.0) = c.categorize(merchant, body, amount).category

    @Test fun `hard-coded brand rules`() {
        assertEquals(TxnCategories.FOOD, cat("SWIGGY LIMITED"))
        assertEquals(TxnCategories.ONLINE_GROCERY, cat("zepto marketplace"))
        assertEquals(TxnCategories.TRANSPORTATION, cat("ICICI FASTAG"))
        assertEquals(TxnCategories.INSURANCE, cat("MAXLIFE INS"))
        assertEquals(TxnCategories.JEWELLERY, cat("Sri Jewel Palace"))
        assertEquals(TxnCategories.WELLNESS, cat("Looks Salon"))
        assertEquals(TxnCategories.TAX, cat("Bruhat Bengaluru Mahanagara Palike"))
        assertEquals(TxnCategories.INVESTMENT, cat("Indian Clearing Corp"))
        assertEquals("ICCL", c.categorize("Indian Clearing Corp", "", 1.0).merchant)
    }

    @Test fun `regex lists`() {
        assertEquals(TxnCategories.ENTERTAINMENT, cat("NETFLIX COM"))
        assertEquals(TxnCategories.HEALTHCARE, cat("APOLLO PHARMACY"))
        assertEquals(TxnCategories.UTILITY_BILL, cat("BESCOM"))
        assertEquals(TxnCategories.PROFESSIONAL, cat("ZOHO CORP"))
        assertEquals(TxnCategories.SPORTS, cat("DECATHLON SPORTS"))
    }

    @Test fun `stem table and hotel amount rule`() {
        assertEquals(TxnCategories.FOOD, cat("Sai Hotel", amount = 400.0))
        assertEquals(TxnCategories.TRAVEL, cat("Sai Hotel", amount = 9000.0))
    }

    @Test fun `early rules and fallbacks`() {
        assertEquals(TxnCategories.CREDIT_CARD_BILL, cat("", "Rs 5000 paid towards credit card bill"))
        assertEquals(TxnCategories.FUND_TRANSFER, cat("", "Rs 5000 debited by AEPS withdrawal"))
        assertEquals(TxnCategories.UNDETECTED, cat(""))
        assertEquals(TxnCategories.UNDETECTED, cat("cash"))
        assertEquals(TxnCategories.MERCHANT_PAY, cat("raz*qwzx", ""))
        assertEquals(TxnCategories.SELF_TRANSFER, cat("self"))
        assertEquals(TxnCategories.PERSONAL_CONTACTS, c.categorize("Ravi", "", 1.0, TxnCategories.PERSONAL_CONTACTS).category)
    }

    @Test fun `merchant clean-up`() {
        assertEquals("SWIGGY", c.clean("rzp*SWIGGY"))
        assertEquals("Zomato", c.clean("your Zomato"))
        assertEquals("ACME", c.clean("IMPS to ACME"))
        assertEquals("Uber", c.clean("Uber for Rs 200"))
        assertEquals("", c.clean("A/c"))
        assertEquals("", c.clean("was not successful"))
        assertEquals("", c.clean("Ref no 12345"))
        assertEquals("Amazon", c.categorize("AMAZON PAY INDIA", "", 1.0).merchant)
        assertEquals("UPI", c.categorize("upi", "", 1.0).merchant)
    }
}
