package com.viswa2k.smsforwarder.core

import org.json.JSONException
import org.json.JSONObject

/** Outcome of one delivery attempt. */
sealed interface SendResult {
    data object Sent : SendResult
    data class Retry(val reason: String, val retryAfterMs: Long? = null) : SendResult
    data class Fatal(val reason: String) : SendResult
}

/** A chat found via `getUpdates`. [topicId] is the forum topic (`message_thread_id`), when the message came from one. */
data class DetectedChat(val chatId: Long, val title: String, val topicId: Long?)

/** Pure interpretation of Telegram Bot API replies (spec 5.4 / 5.6). */
object TelegramResponse {
    fun classify(httpCode: Int, body: String?): SendResult {
        val json = parse(body)
        return when {
            httpCode == 200 ->
                if (json?.optBoolean("ok", false) == true) SendResult.Sent
                else SendResult.Retry("Unexpected Telegram reply")
            httpCode == 429 -> {
                val seconds = json?.optJSONObject("parameters")?.optLong("retry_after", -1L) ?: -1L
                SendResult.Retry("Rate limited by Telegram", if (seconds >= 0) seconds * 1000 else null)
            }
            httpCode == 408 || httpCode >= 500 -> SendResult.Retry("Telegram unavailable (HTTP $httpCode)")
            httpCode in 400..499 -> {
                val description = json?.optString("description", "")?.takeIf { it.isNotBlank() }
                SendResult.Fatal(description ?: "HTTP $httpCode")
            }
            else -> SendResult.Retry("Unexpected HTTP $httpCode")
        }
    }

    /** Distinct chats (by chat id + topic) from a `getUpdates` reply, in first-seen order. Bad JSON gives an empty list. */
    fun parseChats(getUpdatesJson: String): List<DetectedChat> {
        val result = parse(getUpdatesJson)?.optJSONArray("result") ?: return emptyList()
        val seen = LinkedHashMap<Pair<Long, Long?>, DetectedChat>()
        for (i in 0 until result.length()) {
            val update = result.optJSONObject(i) ?: continue
            val message = SOURCES.firstNotNullOfOrNull { update.optJSONObject(it) } ?: continue
            val chat = message.optJSONObject("chat") ?: continue
            if (!chat.has("id")) continue
            val chatId = chat.optLong("id")
            val inTopic = message.optBoolean("is_topic_message", false) || chat.optBoolean("is_forum", false)
            val topic = if (inTopic && message.has("message_thread_id")) message.optLong("message_thread_id") else null
            val title = listOf("title", "first_name", "username")
                .firstNotNullOfOrNull { chat.optString(it, "").takeIf { s -> s.isNotBlank() } }
                ?: chatId.toString()
            seen.getOrPut(chatId to topic) { DetectedChat(chatId, title, topic) }
        }
        return seen.values.toList()
    }

    /** Removes bot tokens from text that may contain a request URL (`bot<token>/` becomes `bot<redacted>/`). */
    fun redact(text: String): String = BOT_PATH.replace(text, "bot<redacted>")

    private val SOURCES = listOf("message", "edited_message", "channel_post", "edited_channel_post")
    private val BOT_PATH = Regex("bot[^/\\s\"']+(?=/)")

    private fun parse(body: String?): JSONObject? =
        if (body.isNullOrBlank()) null else try { JSONObject(body) } catch (_: JSONException) { null }
}
