package com.viswa2k.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramResponseTest {
    @Test fun okIsSent() {
        assertEquals(SendResult.Sent, TelegramResponse.classify(200, """{"ok":true,"result":{}}"""))
    }

    @Test fun http200WithoutOkIsRetry() {
        assertTrue(TelegramResponse.classify(200, "garbage") is SendResult.Retry)
        assertTrue(TelegramResponse.classify(200, null) is SendResult.Retry)
    }

    @Test fun tooManyRequestsHonoursRetryAfter() {
        val r = TelegramResponse.classify(429, """{"ok":false,"error_code":429,"parameters":{"retry_after":7}}""")
        assertEquals(7000L, (r as SendResult.Retry).retryAfterMs)
    }

    @Test fun tooManyRequestsWithoutParameterRetriesWithBackoff() {
        val r = TelegramResponse.classify(429, "{}") as SendResult.Retry
        assertEquals(null, r.retryAfterMs)
    }

    @Test fun chatNotFoundIsFatalWithDescription() {
        val r = TelegramResponse.classify(400, """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""")
        assertEquals("Bad Request: chat not found", (r as SendResult.Fatal).reason)
    }

    @Test fun clientErrorsAreFatal() {
        for (code in listOf(400, 401, 403, 404)) {
            assertTrue("$code", TelegramResponse.classify(code, """{"description":"x"}""") is SendResult.Fatal)
        }
        assertEquals("HTTP 401", (TelegramResponse.classify(401, null) as SendResult.Fatal).reason)
    }

    @Test fun serverErrorsRetry() {
        assertTrue(TelegramResponse.classify(502, "<html>") is SendResult.Retry)
        assertTrue(TelegramResponse.classify(500, null) is SendResult.Retry)
    }

    private val updates = """
    {"ok":true,"result":[
      {"update_id":1,"message":{"message_id":1,"chat":{"id":111,"type":"private","first_name":"Ravi","username":"ravi"},"text":"hi"}},
      {"update_id":2,"message":{"message_id":2,"chat":{"id":111,"type":"private","first_name":"Ravi"},"text":"again"}},
      {"update_id":3,"message":{"message_id":3,"chat":{"id":-1001,"type":"group","title":"Family"},"text":"yo"}},
      {"update_id":4,"message":{"message_id":4,"message_thread_id":55,"chat":{"id":-1002,"type":"supergroup","title":"Phones","is_forum":true},"text":"t"}},
      {"update_id":5,"message":{"message_id":5,"message_thread_id":55,"chat":{"id":-1002,"type":"supergroup","title":"Phones","is_forum":true},"text":"t2"}},
      {"update_id":6,"message":{"message_id":6,"message_thread_id":56,"chat":{"id":-1002,"type":"supergroup","title":"Phones","is_forum":true},"text":"t3"}},
      {"update_id":7,"channel_post":{"message_id":7,"chat":{"id":-1003,"type":"channel","title":"News"},"text":"c"}},
      {"update_id":8,"message":{"message_id":8,"chat":{"id":222,"type":"private","username":"bob"},"text":"u"}},
      {"update_id":9,"edited_message":{"message_id":8,"chat":{"id":333,"type":"private","first_name":"Eve"},"text":"u"}},
      {"update_id":10}
    ]}"""

    @Test fun parseChatsDedupesByChatAndTopic() {
        val chats = TelegramResponse.parseChats(updates)
        assertEquals(
            listOf(
                DetectedChat(111, "Ravi", null),
                DetectedChat(-1001, "Family", null),
                DetectedChat(-1002, "Phones", 55),
                DetectedChat(-1002, "Phones", 56),
                DetectedChat(-1003, "News", null),
                DetectedChat(222, "bob", null),
                DetectedChat(333, "Eve", null),
            ),
            chats,
        )
    }

    @Test fun parseChatsToleratesBadJson() {
        assertEquals(emptyList<DetectedChat>(), TelegramResponse.parseChats("nope"))
        assertEquals(emptyList<DetectedChat>(), TelegramResponse.parseChats("""{"ok":false}"""))
    }

    @Test fun redactHidesTokenInUrls() {
        assertEquals(
            "Unable to resolve https://api.telegram.org/bot<redacted>/sendMessage",
            TelegramResponse.redact("Unable to resolve https://api.telegram.org/bot123:ABC-def_x/sendMessage"),
        )
    }
}
