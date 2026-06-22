package com.viswa2k.smsforwarder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SmsReceiver : BroadcastReceiver() {
    var globalMessageFormat = ""
    var deviceAlias = ""

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Log.d("Message Receiver", "Received message")

        // Parse PDUs synchronously (fast, no I/O) before handing off to a background coroutine.
        val messages = mutableListOf<Pair<String?, String>>()
        try {
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>
                for (pdu in pdus) {
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                    messages.add(smsMessage.displayOriginatingAddress to smsMessage.messageBody)
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing SMS")
        }

        // Forward off the main thread; goAsync() keeps the process alive until finish().
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((senderNumber, messageBody) in messages) {
                    forwardMessage(context, senderNumber, messageBody)
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error forwarding SMS")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun isSenderInContacts(context: Context, senderNumber: String?): Boolean {
        if (senderNumber.isNullOrEmpty()) return false

        val contentResolver: ContentResolver = context.contentResolver
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(senderNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)

        val cursor: Cursor? = contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )

        return cursor?.use {
            it.count > 0
        } ?: false
    }

    private suspend fun forwardMessage(context: Context, senderNumber: String?, messageBody: String) {
        val prefs: Preferences = context.dataStore.data.first()

        // Master switch: the receiver is always registered (static), but only forward when the
        // user has turned forwarding on. With the master on, each channel (SMS/Telegram/Cloud)
        // is gated by its own flag below — so a cloud-only setup just needs Enable Service +
        // Upload to cloud.
        val serviceEnabled = prefs[booleanPreferencesKey("SMS_FORWARD_SERVICE")] ?: false
        if (!serviceEnabled) {
            Log.d("SmsReceiver", "Forwarding master switch off; ignoring SMS")
            return
        }

        val isSkipContacts = prefs[booleanPreferencesKey("IS_SKIP_CONTACTS")] ?: false
        if (isSkipContacts && isSenderInContacts(context, senderNumber)) {
            return
        }

        globalMessageFormat = prefs[stringPreferencesKey("GLOBAL_MESSAGE_FORMAT")] ?: ""
        deviceAlias = prefs[stringPreferencesKey("DEVICE_ALIAS")] ?: ""
        if (globalMessageFormat.isBlank()) globalMessageFormat = "%m"
        if (deviceAlias.isBlank()) deviceAlias = "${Build.MANUFACTURER} ${Build.MODEL}"

        val isBySmsEnabled = prefs[booleanPreferencesKey("IS_BY_SMS_ENABLED")] ?: false
        if (isBySmsEnabled) {
            val smsToNumber = prefs[stringPreferencesKey("SMS_TO_NUMBER")] ?: ""
            val smsMessageFormat = prefs[stringPreferencesKey("SMS_FORWARD_FORMAT")] ?: ""
            if (smsToNumber.isNotEmpty()) {
                sendSms(context, smsToNumber, senderNumber.toString(), messageBody, smsMessageFormat)
            }
        }

        val isByTelegramEnabled = prefs[booleanPreferencesKey("IS_BY_TELEGRAM_ENABLED")] ?: false
        if (isByTelegramEnabled) {
            val telegramMessageFormat = prefs[stringPreferencesKey("TELEGRAM_SEND_FORMAT")] ?: ""
            val telegramApiKey = prefs[stringPreferencesKey("TELEGRAM_API_KEY")] ?: ""
            val telegramUserIds = prefs[stringPreferencesKey("TELEGRAM_USER_IDS")] ?: ""
            if (telegramApiKey.isNotEmpty() && telegramUserIds.isNotEmpty()) {
                sendTelegramMessage(telegramApiKey, telegramUserIds, senderNumber.toString(), messageBody, telegramMessageFormat)
            }
        }

        val isCloudEnabled = prefs[booleanPreferencesKey("IS_CLOUD_CHANNEL_ENABLED")] ?: false
        if (isCloudEnabled) {
            try {
                com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader.get(context)
                    .upload(senderNumber.toString(), messageBody, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Cloud upload error", e)
            }
        }
    }

    private fun sendSms(context: Context, to: String, from: String, message: String, messageFormat: String) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            // Create PendingIntents for sent and delivery statuses
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent("SMS_SENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // Use FLAG_IMMUTABLE
            )
            val deliveredIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent("SMS_DELIVERED"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // Use FLAG_IMMUTABLE
            )

            var format = messageFormat
            if (format.isBlank()) {
                format = globalMessageFormat
            }
            val content = format.replace("%s", from).replace("%r", deviceAlias).replace("%m", message)

            // Send SMS, supporting long (multipart) messages
            val parts = smsManager.divideMessage(content)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(sentIntent)
                    deliveredIntents.add(deliveredIntent)
                }
                smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents)
            } else {
                smsManager.sendTextMessage(to, null, content, sentIntent, deliveredIntent)
            }
        } catch (e: Exception) {
            Log.e("SmsSender", "Failed to send SMS")
        }
    }

    fun sendTelegramMessage(apiKey: String, userIds: String, from: String, message: String, messageFormat: String) {
        val baseUrl = "https://api.telegram.org/bot$apiKey/sendMessage"
        var format = messageFormat
        if (format.isBlank()) {
            format = globalMessageFormat
        }
        val content = format.replace("%s", escapeHtml(from))
            .replace("%r", deviceAlias)
            .replace("%m", escapeHtml(message))
            .replace("\\n", "\n")

        userIds.split(",").forEach { userId ->
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection

            try {
                // Configure the connection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                // Prepare the message payload
                val payload = "chat_id=$userId&text=${URLEncoder.encode(content, "UTF-8")}&parse_mode=HTML"
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                // Get the response code
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d("TelegramAPI", "Message sent")
                } else {
                    Log.e("TelegramAPI", "Failed to send message: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e("TelegramAPI", "Error sending message")
            } finally {
                connection.disconnect()
            }
        }
    }

}
