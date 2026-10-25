package com.viswa2k.smsforwarder.delivery

import com.viswa2k.smsforwarder.core.DetectedChat
import com.viswa2k.smsforwarder.core.MessageFormatter
import com.viswa2k.smsforwarder.core.SendResult
import com.viswa2k.smsforwarder.core.TelegramResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Telegram Bot API over [HttpURLConnection]. The token is read per call and never appears in an
 * exception or log: every failure is rethrown/returned with the URL redacted (`bot<redacted>`).
 */
class TelegramClient(private val tokenProvider: suspend () -> String?) {

    suspend fun send(chatId: String, topicId: Long?, html: String): SendResult {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return SendResult.Fatal("Bot token not set")
        val params = linkedMapOf("chat_id" to chatId, "text" to html, "parse_mode" to "HTML", "disable_web_page_preview" to "true")
        if (topicId != null) params["message_thread_id"] = topicId.toString()
        return try {
            val (code, body) = post(token, "sendMessage", params)
            TelegramResponse.classify(code, body)
        } catch (e: IOException) {
            SendResult.Retry("Network error: ${e.message}")
        }
    }

    suspend fun detectChats(): Result<List<DetectedChat>> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return Result.failure(IOException("Bot token not set"))
        return try {
            val (code, body) = post(token, "getUpdates", mapOf("timeout" to "0"))
            if (code == 200 && body != null) {
                Result.success(TelegramResponse.parseChats(body))
            } else {
                Result.failure(IOException((TelegramResponse.classify(code, body) as? SendResult.Fatal)?.reason ?: "HTTP $code"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun test(chatId: String, topicId: Long?, deviceName: String): SendResult =
        send(chatId, topicId, "✅ Test from ${MessageFormatter.escapeHtml(deviceName)}")

    /** Throws only [IOException]s whose message and cause are free of the token. */
    private suspend fun post(token: String, method: String, params: Map<String, String>): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                }
                val form = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (e: Exception) {
                // Fresh exception, no cause: the original may carry the request URL.
                throw IOException(clean("${e.javaClass.simpleName}: ${e.message.orEmpty()}", token))
            } finally {
                conn?.disconnect()
            }
        }

    private fun clean(text: String, token: String): String =
        TelegramResponse.redact(if (token.isEmpty()) text else text.replace(token, "<redacted>"))

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
