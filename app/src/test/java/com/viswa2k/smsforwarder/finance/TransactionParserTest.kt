// Ported from SMS app: app/src/test/java/com/viswa2k/sms/finance/TransactionParserTest.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.TestKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransactionParserTest {

    private val parser = TransactionParser(TestKeywords.lists)

    /** Runs every corpus case and reports all mismatches at once. */
    @Test fun `labelled corpus`() {
        val failures = ArrayList<String>()
        for (c in TransactionCorpus.cases) {
            val r = parser.parse(c.sender, c.body)
            val problems = ArrayList<String>()
            fun check(name: String, expected: Any?, actual: Any?) {
                if (expected != null && expected != actual) problems += "$name expected <$expected> got <$actual>"
            }
            check("status", c.status, r.status)
            check("amount", c.amount, r.amount)
            check("direction", c.direction, r.direction)
            check("account", c.account, r.accountLast4)
            check("card", c.card, r.cardLast4)
            check("cardType", c.cardType, r.cardType)
            check("balance", c.balance, r.balance)
            if (c.noBalance && r.balance != null) problems += "balance expected none got ${r.balance}"
            check("outstanding", c.outstanding, r.outstanding)
            check("mode", c.mode, r.paymentMode)
            check("category", c.category, r.category)
            check("upiRef", c.upiRef, r.upiRef)
            check("vpa", c.vpa, r.vpa)
            check("kind", c.kind, r.kind)
            check("uncertain", c.uncertain, r.accountUncertain)
            check("subType", c.subType, r.subType)
            check("cancels", c.cancels, r.cancelsMatchingDebit)
            check("accountType", c.accountType, r.accountType)
            check("paidCard", c.paidCard, r.paidCardLast4)
            if (c.merchant != null && !r.merchant.contains(c.merchant, ignoreCase = true)) {
                problems += "merchant expected ~<${c.merchant}> got <${r.merchant}>"
            }
            if (problems.isNotEmpty()) failures += "[${c.id}] ${problems.joinToString("; ")}  (rule: ${r.rule})"
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${TransactionCorpus.cases.size} corpus cases failed:\n" + failures.joinToString("\n"))
    }

    @Test fun `corpus covers every status and direction`() {
        val statuses = TransactionCorpus.cases.map { it.status }.toSet()
        assertTrue(statuses.containsAll(ParseStatus.entries))
        assertTrue(TransactionCorpus.cases.any { it.direction == TxnDirection.CREDIT })
        assertTrue(TransactionCorpus.cases.any { it.direction == TxnDirection.DEBIT })
        assertTrue(TransactionCorpus.cases.size >= 50)
    }

    @Test fun `parser never throws on junk`() {
        val junk = listOf("", " ", "a/c", "Rs", "Rs.", "xx", "**..**", "a/c XX", "Rs 1,2,3,4.5.6 debited a/c xx1",
            "card ".repeat(500), "₹".repeat(100), "\n\n\n", "a/c xx12 ".repeat(200) + "Rs 5 debited")
        for (j in junk) parser.parse("AX-HDFCBK", j)
    }

    @Test fun `money helpers`() {
        assertEquals(123450L, Money.toMinor(1234.50))
        assertEquals(1999L, Money.toMinor(19.99))
        assertEquals("₹ 1,23,456", Money.format(12345600))
        assertEquals("₹ 500", Money.format(50000))
        assertEquals("₹ 1,234.50", Money.format(123450))
        assertEquals("-₹ 12,34,567.05", Money.format(-123456705))
        assertEquals("₹ 999", Money.format(99900))
    }

    @Test fun `number clean-up rule`() {
        assertEquals(1234.5, FinanceText.cleanNumber("Rs.1,234.50"), 0.0)
        assertEquals(1234.0, FinanceText.cleanNumber("1,234."), 0.0)
        assertEquals(500.0, FinanceText.cleanNumber("INR 500/-"), 0.0)
        assertEquals(0.0, FinanceText.cleanNumber("abc"), 0.0)
        assertEquals(123456.0, FinanceText.cleanNumber("1,23,456"), 0.0)
    }

    @Test fun `text preparation canonicalises currency and masks`() {
        val t = FinanceText.prepare("₹1,000 debited from A/c **1234.\nAvailable Balance Rs.50")
        assertTrue(t, t.contains("Rs 1,000"))
        assertTrue(t, t.contains("XX1234"))
        assertTrue(t, t.contains("avl bal"))
        assertTrue(t, t.contains(FinanceText.NL.trim()))
        assertTrue(FinanceText.prepare("card 4111 XXXX XXXX 2233 used").contains("XX2233"))
        assertTrue(FinanceText.prepare("a/c no. xxxxxx5678").contains("XX5678"))
        // promotional amounts are blanked
        assertFalse(FinanceText.prepare("Get Rs 500 off on orders").contains("500"))
        assertFalse(FinanceText.prepare("cashback up to Rs 1000 on bills").contains("1000"))
    }

    @Test fun `card type rules`() {
        assertEquals(CardTypes.MEAL, AccountStages.cardType("meal card"))
        assertEquals(CardTypes.PREPAID, AccountStages.cardType("Prepaid card"))
        assertEquals(CardTypes.CREDIT, AccountStages.cardType("Credit Card"))
        assertEquals(CardTypes.DEBIT, AccountStages.cardType("credit/debit card"))
        assertEquals(CardTypes.DEBIT, AccountStages.cardType("card"))
    }

    @Test fun `sub-type check`() {
        assertEquals("loan", AccountStages.subTypeBefore("your loan a/c", 10))
        assertEquals("wallet", AccountStages.subTypeBefore("my wallet a/c", 10))
        assertEquals("fastag", AccountStages.subTypeBefore("fastag a/c", 7))
        assertEquals("Fixed Deposit", AccountStages.subTypeBefore("your FD a/c", 8))
        assertNull(AccountStages.subTypeBefore("your a/c", 5))
    }
}

class TransactionParserSpeedTest {
    /** Guards against pathological regex backtracking: the whole corpus ×20 must parse in seconds. */
    @org.junit.Test fun `corpus parses quickly`() {
        val parser = TransactionParser(TestKeywords.lists)
        TransactionCorpus.cases.forEach { parser.parse(it.sender, it.body) } // warm-up (lazy tables)
        val start = System.nanoTime()
        repeat(20) { TransactionCorpus.cases.forEach { parser.parse(it.sender, it.body) } }
        val perMessageMs = (System.nanoTime() - start) / 1e6 / (20 * TransactionCorpus.cases.size)
        org.junit.Assert.assertTrue("too slow: $perMessageMs ms/message", perMessageMs < 50.0)
    }
}

class DebitCreditBindingTest {
    private val parser = TransactionParser(TestKeywords.lists)

    @org.junit.Test fun `debited from your account stays a debit whatever is credited`() {
        // Two different own accounts: a real debit (the tagging pass pairs it with the credit).
        val own = parser.parse("AX-HDFCBK", "Rs 900.00 debited from your a/c XX1234 and credited to your a/c XX5678 on 12-01-24.")
        org.junit.Assert.assertEquals(TxnDirection.DEBIT, own.direction)
        org.junit.Assert.assertEquals("1234", own.accountLast4)
        val pay = parser.parse("AX-HDFCBK", "Rs 900.00 debited from your a/c XX1234 and credited to your beneficiary RAVI on 12-01-24.")
        org.junit.Assert.assertEquals(pay.rule, TxnDirection.DEBIT, pay.direction)
    }
}
