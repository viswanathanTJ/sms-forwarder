package com.viswa2k.smsforwarder.delivery

import com.viswa2k.smsforwarder.core.MessageFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherTextTest {
    @Test fun blankTelegramTextFallsBackToEscapedOriginal() {
        assertEquals("a &lt; b &amp; c", prepareTelegramText("  ", "a < b & c"))
        assertEquals("a &lt; b &amp; c", prepareTelegramText("", "a < b & c"))
    }

    @Test fun normalTelegramTextIsUntouched() {
        assertEquals("<b>x</b>", prepareTelegramText("<b>x</b>", "ignored"))
    }

    @Test fun textAtTheLimitIsUntouched() {
        val text = "x".repeat(MessageFormatter.TELEGRAM_MAX)
        assertEquals(text, prepareTelegramText(text, "body"))
    }

    @Test fun overlongTextIsCappedAtLimit() {
        val out = prepareTelegramText("<i>" + "x".repeat(5000) + "</i>", "y".repeat(5000))
        assertEquals(MessageFormatter.TELEGRAM_MAX, out.length)
        assertTrue(out.endsWith("…"))
        assertTrue(!out.contains("<"))
    }

    @Test fun cutNeverSplitsAnEntity() {
        // "x" + 2000 x "&amp;": the 4095 cut would land inside an entity.
        val out = prepareTelegramText("z".repeat(5000), "x" + "&".repeat(2000))
        assertTrue(out.length <= MessageFormatter.TELEGRAM_MAX)
        assertTrue(out.endsWith("…"))
        assertTrue(Regex("x(&amp;)*…").matches(out))
    }

    @Test fun cutNeverSplitsASurrogatePair() {
        val out = prepareTelegramText("z".repeat(5000), "😀".repeat(3000))
        assertTrue(out.length <= MessageFormatter.TELEGRAM_MAX)
        val body = out.removeSuffix("…")
        assertTrue(Character.isLowSurrogate(body.last()))
        assertEquals(0, body.length % 2)
    }

    @Test fun blankSmsTextFallsBackToOriginal() {
        assertEquals("hello", prepareSmsText("", "hello"))
        assertEquals("hello", prepareSmsText("  ", "hello"))
        assertEquals("fmt", prepareSmsText("fmt", "hello"))
    }
}
