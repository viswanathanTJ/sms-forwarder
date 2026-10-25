package com.viswa2k.smsforwarder.ui.rules

import com.viswa2k.smsforwarder.classify.TestKeywords
import com.viswa2k.smsforwarder.core.MessageAnalyzer
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.core.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RuleEditorStateTest {
    private val analyzer = MessageAnalyzer(TestKeywords.lists)
    private val zone = ZoneId.of("UTC")
    private fun ready() = RuleEditorState(name = "R", destinationIds = setOf(1L))

    @Test fun allMessages_clearsTypes() {
        val s = ready().toggleType(MessageType.OTP).toggleType(MessageType.TRANSACTION).toggleAll()
        assertTrue(s.allMessages)
        assertTrue(s.types.isEmpty())
    }

    @Test fun selectingType_clearsAll() {
        val s = ready().toggleAll().toggleType(MessageType.OTP)
        assertFalse(s.allMessages)
        assertEquals(setOf(MessageType.OTP), s.types)
    }

    @Test fun toggleTwice_removesType_andAllTogglesOff() {
        assertTrue(ready().toggleType(MessageType.OTP).toggleType(MessageType.OTP).types.isEmpty())
        assertFalse(ready().toggleAll().toggleAll().allMessages)
    }

    @Test fun spamIsNeverSelectable() {
        assertTrue(ready().toggleType(MessageType.SPAM).types.isEmpty())
    }

    @Test fun toggleDestination_addsAndRemoves() {
        val s = RuleEditorState().toggleDestination(2).toggleDestination(3)
        assertEquals(setOf(2L, 3L), s.destinationIds)
        assertEquals(setOf(3L), s.toggleDestination(2).destinationIds)
    }

    @Test fun canSave_needsNameTypeAndDestination() {
        val ok = ready().toggleType(MessageType.OTP)
        assertTrue(ok.canSave)
        assertTrue(ready().toggleAll().canSave)
        assertFalse(ok.copy(name = "  ").canSave)
        assertFalse(ready().canSave)
        assertFalse(ok.copy(destinationIds = emptySet()).canSave)
    }

    @Test fun roundTrip_ruleToStateToRule() {
        val rule = Rule(7, "Mine", false, false, setOf(MessageType.OTP), SenderCondition.CONTACTS_ONLY, listOf(2, 1), Style.CUSTOM, "%s", 4)
        val back = RuleEditorState.from(rule).toRule()
        assertEquals(rule.copy(destinationIds = listOf(1, 2)), back)
    }

    @Test fun toRule_dropsTemplateUnlessCustom_andKeepsAllMessagesClean() {
        val r = ready().toggleAll().copy(style = Style.SMART, customTemplate = "x").toRule()
        assertEquals(null, r.customTemplate)
        assertTrue(r.allMessages)
        assertTrue(r.types.isEmpty())
    }

    @Test fun preview_transactionStartsWithMoneyBag() {
        val p = ready().toggleType(MessageType.TRANSACTION).preview(analyzer, "Pixel 8", zone)
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].startsWith("💸"))
    }

    @Test fun preview_otpAndMissedCall_upToTwo() {
        val s = ready().toggleType(MessageType.OTP).toggleType(MessageType.MISSED_CALL).toggleType(MessageType.TRANSACTION)
        val p = s.preview(analyzer, "Pixel 8", zone)
        assertEquals(2, p.size)
        assertTrue(p[0], p[0].startsWith("🔐 OTP <code>482913</code>"))
        assertTrue(p[1], p[1].startsWith("💸") || p[1].startsWith("📞"))
    }

    @Test fun preview_missedCall() {
        val p = ready().toggleType(MessageType.MISSED_CALL).preview(analyzer, "Pixel 8", zone)
        assertTrue(p[0], p[0].startsWith("📞 Missed call"))
    }

    @Test fun preview_allOrNoSample_fallsBackToTransaction() {
        assertTrue(ready().toggleAll().preview(analyzer, "P", zone)[0].startsWith("💸"))
        assertTrue(ready().toggleType(MessageType.DELIVERY).preview(analyzer, "P", zone)[0].startsWith("💸"))
    }

    @Test fun preview_customStyleUsesTemplate() {
        val s = ready().toggleType(MessageType.OTP).copy(style = Style.CUSTOM, customTemplate = "%r|%o")
        assertEquals(listOf("Pixel 8|482913"), s.preview(analyzer, "Pixel 8", zone))
    }
}

class OneShotAndDestinationsTest {
    @Test fun oneShot_runsOnce() {
        val o = OneShot()
        assertFalse(o.isFired)
        assertTrue(o.tryRun())
        assertFalse(o.tryRun())
        assertTrue(o.isFired)
    }

    @Test fun retainDestinations_dropsDeletedIds_andCanSaveFollows() {
        val s = RuleEditorState(name = "R", allMessages = true, destinationIds = setOf(1L, 2L))
        assertEquals(setOf(1L), s.retainDestinations(setOf(1L, 5L)).destinationIds)
        assertTrue(s.retainDestinations(setOf(1L)).canSave)
        assertFalse(s.retainDestinations(setOf(9L)).canSave)
    }
}

class TelegramHtmlTest {
    @Test fun boldItalicCodeAndEntities() {
        val a = telegramHtmlToAnnotated("🔐 <b>A &amp; B</b> <code>123</code> <i>x &lt;y&gt;</i>")
        assertEquals("🔐 A & B 123 x <y>", a.text)
        fun covered(pick: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
            a.spanStyles.filter { pick(it.item) }.joinToString("") { a.text.substring(it.start, it.end) }
        assertEquals("A & B", covered { it.fontWeight != null })
        assertEquals("123", covered { it.fontFamily != null })
        assertEquals("x <y>", covered { it.fontStyle != null })
    }
}
