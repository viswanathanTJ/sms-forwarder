package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun `housekeeping texts are recognised`() {
        listOf(
            "3 new messages", "1 new message", "Checking for new messages", "5 messages from 2 chats",
            "2 messages from 1 chat", "Backup in progress", "Backing up messages", "Restoring media",
            "Incoming voice call", "Ongoing video call", "Missed voice call", "2 missed calls", "Calling…", "Ringing…",
            "  4 NEW MESSAGES  ",
        ).forEach { assertTrue(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `real messages are not housekeeping`() {
        listOf(
            "482913 is your Amazon OTP", "I have 3 new messages for you tomorrow", "Call me when free",
            "Rs 500 debited from A/c XX1234", "",
        ).forEach { assertFalse(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `sender address for chats and groups`() {
        assertEquals("WA:Amazon", NotificationText.senderAddress(" Amazon ", null))
        assertEquals("WA:Amazon", NotificationText.senderAddress("Amazon", "Amazon"))
        assertEquals("WA:Family · Mom", NotificationText.senderAddress("Family", "Mom"))
        assertEquals("WA:Family", NotificationText.senderAddress("Family", " "))
    }

    @Test fun `display name and chat of an address`() {
        assertEquals("WhatsApp · Amazon", NotificationText.displayName("WA:Amazon"))
        assertEquals("WhatsApp · Family · Mom", NotificationText.displayName("WA:Family · Mom"))
        assertEquals("VM-HDFCBK", NotificationText.displayName("VM-HDFCBK"))
        assertEquals("Family", NotificationText.chatOf("WA:Family · Mom"))
        assertEquals("Amazon", NotificationText.chatOf("WA:Amazon"))
    }

    @Test fun `whatsapp address detection`() {
        assertTrue(NotificationText.isWhatsAppAddress("WA:Amazon"))
        assertFalse(NotificationText.isWhatsAppAddress("VM-HDFCBK"))
        assertFalse(NotificationText.isWhatsAppAddress("WAYBIL"))
        assertFalse(NotificationText.isWhatsAppAddress("+919840012345"))
    }

    @Test fun `timestamps older than ten minutes before the post are rejected`() {
        val post = 10_000_000L
        assertTrue(NotificationText.acceptTimestamp(post, post, 0))
        assertTrue(NotificationText.acceptTimestamp(post - 600_000, post, 0))
        assertFalse(NotificationText.acceptTimestamp(post - 600_001, post, 0))
        assertTrue(NotificationText.acceptTimestamp(post + 5_000, post, 0))
    }

    @Test fun `timestamps before the toggle was switched on are rejected`() {
        val post = 10_000_000L
        assertFalse(NotificationText.acceptTimestamp(post - 1_000, post, waFloor = post - 500))
        assertTrue(NotificationText.acceptTimestamp(post - 500, post, waFloor = post - 500))
    }

    @Test fun `accept combines text, housekeeping and time rules`() {
        val post = 10_000_000L
        fun m(text: String, ts: Long = post) = WaMessage("Amazon", null, text, ts)
        assertTrue(NotificationText.accept(m("482913 is your OTP"), post, 0))
        assertFalse(NotificationText.accept(m("  "), post, 0))
        assertFalse(NotificationText.accept(m("3 new messages"), post, 0))
        assertFalse(NotificationText.accept(m("482913 is your OTP", post - 3_600_000), post, 0))
    }

    @Test fun `only whatsapp packages are allowed`() {
        assertTrue(NotificationText.isAllowedPackage("com.whatsapp"))
        assertTrue(NotificationText.isAllowedPackage("com.whatsapp.w4b"))
        assertFalse(NotificationText.isAllowedPackage("com.google.android.apps.messaging"))
        assertFalse(NotificationText.isAllowedPackage(null))
    }

    @Test fun `origin codes are stable`() {
        assertEquals(0, Origin.SMS.code)
        assertEquals(1, Origin.WHATSAPP.code)
        assertEquals(Origin.WHATSAPP, Origin.fromCode(1))
        assertEquals(Origin.SMS, Origin.fromCode(7))
    }

    @Test fun `titles lose the unread counter and bidi marks`() {
        assertEquals("Family", NotificationText.cleanTitle("Family (5 messages)"))
        assertEquals("Ravi", NotificationText.cleanTitle("\u200ERavi\u200F (2 new messages)"))
        assertEquals("Amazon (India)", NotificationText.cleanTitle("Amazon (India)"))
        assertEquals("WA:Family · Mom", NotificationText.senderAddress("Family (3 messages)", "Mom"))
    }

    @Test fun `call notices, deletions and media placeholders are housekeeping`() {
        listOf(
            "Missed voice call", "Missed video call at 10:31", "Incoming voice call", "Incoming video call",
            "Calling…", "This message was deleted", "You deleted this message",
            "📷 Photo", "🎤 Voice message (0:12)", "📄 Document", "🎥 Video", "Sticker", "👾 Sticker", "GIF", "📷\uFE0F Photo",
        ).forEach { assertTrue(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `messages that merely start like a notice are kept`() {
        listOf(
            "Calling you later", "📷 Look at this bill", "Photo of the receipt is attached, OTP 482913",
            "Video KYC OTP is 482913", "Your document is ready",
        ).forEach { assertFalse(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `reactions are housekeeping`() {
        listOf(
            "Reacted 🙇 to \"Sure my pleasure..\"", "Reacted to \"…\"", "Reacted 👍🏽 to “Thanks”",
            "❤️ reacted to your message", "👍 reacted to your photo", "Reacted 😂 to: \"lol\"",
            "Reacted 👍 to a message", "Reacted ❤️ to your photo", "Reacted 🙏 to a GIF", "Reacted 😮 to your voice message",
            "Reacted to a sticker", "Reacted 👍 to your document",
        ).forEach { assertTrue(it, NotificationText.housekeeping(it)) }
    }

    @Test fun `messages that merely start with reacted are kept`() {
        listOf("Reacted quickly, OTP 123456", "Reacted to the alert? OTP 482913", "reacted", "Reacted to a messy bill, OTP 1234").forEach {
            assertFalse(it, NotificationText.housekeeping(it))
        }
    }

    // ---- select(): which (chat, person, text, time) a notification carries ------------------------

    private fun msg(text: String?, ts: Long = 1_000, name: String? = "Ravi", key: String? = null, person: Boolean = true) =
        StyleMessage(text, ts, if (person) name else null, if (person) key else null, hasPerson = person)

    @Test fun `one-to-one chat uses the person name, else the title`() {
        val out = NotificationText.select(
            title = "Ravi (2 messages)", isGroup = false, conversationTitle = null, userKey = null,
            messages = listOf(msg("hi"), msg("there", name = null)), fallbackText = null, fallbackTs = 0,
        )
        assertEquals(listOf(WaMessage("Ravi", null, "hi", 1_000), WaMessage("Ravi (2 messages)", null, "there", 1_000)), out)
    }

    @Test fun `group chat uses the conversation title and keeps the member`() {
        val out = NotificationText.select(
            title = "Family: Mom", isGroup = true, conversationTitle = "Family", userKey = null,
            messages = listOf(msg("OTP 1234", name = "Mom")), fallbackText = null, fallbackTs = 0,
        )
        assertEquals(listOf(WaMessage("Family", "Mom", "OTP 1234", 1_000)), out)
    }

    @Test fun `group without conversation title falls back to the notification title`() {
        val out = NotificationText.select("Family", true, null, null, listOf(msg("x", name = "Mom")), null, 0)
        assertEquals(listOf(WaMessage("Family", "Mom", "x", 1_000)), out)
    }

    @Test fun `own messages and empty texts are skipped`() {
        val out = NotificationText.select(
            title = "Ravi", isGroup = false, conversationTitle = null, userKey = "me",
            messages = listOf(msg("mine", person = false), msg("also mine", name = "You", key = "me"), msg(null), msg("theirs", key = "ravi")),
            fallbackText = null, fallbackTs = 0,
        )
        assertEquals(listOf(WaMessage("Ravi", null, "theirs", 1_000)), out)
    }

    @Test fun `messages labelled You are the owner's own`() {
        val out = NotificationText.select(
            title = "Family", isGroup = true, conversationTitle = "Family", userKey = "me",
            messages = listOf(msg("mine", name = "You", key = "other"), msg("mine too", name = " you "), msg("OTP 1234", name = "Mom")),
            fallbackText = null, fallbackTs = 0,
        )
        assertEquals(listOf(WaMessage("Family", "Mom", "OTP 1234", 1_000)), out)
        // A 1:1 chat with no user key either.
        assertEquals(emptyList<WaMessage>(), NotificationText.select("Ravi", false, null, null, listOf(msg("hey", name = "YOU")), null, 0))
    }

    @Test fun `without messaging style the big or plain text is used under the title`() {
        assertEquals(
            listOf(WaMessage("Amazon", null, "482913 is your OTP", 7_000)),
            NotificationText.select("Amazon", false, null, null, null, "482913 is your OTP", 7_000),
        )
        assertEquals(emptyList<WaMessage>(), NotificationText.select("Amazon", false, null, null, null, null, 7_000))
        // An empty MessagingStyle also falls back.
        assertEquals(1, NotificationText.select("Amazon", false, null, null, emptyList(), "x", 7_000).size)
    }

    @Test fun `after a log clear re-posted history is not forwarded again`() {
        // Clear deleted the dedupe keys and moved waFloor to "now" (LogViewModel.clear).
        val clearedAt = 10_000_000L
        val waFloor = LogClearPolicy.floorAfterClear(clearedAt, currentFloor = 1_000L)
        val repostAt = clearedAt + 60_000
        assertFalse(NotificationText.acceptTimestamp(clearedAt - 120_000, repostAt, waFloor))
        assertTrue(NotificationText.acceptTimestamp(clearedAt + 1_000, repostAt, waFloor))
    }
}
