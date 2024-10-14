package com.viswa2k.smsforwarder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {

    private fun isSenderInContacts(context: Context, senderNumber: String?): Boolean {
        if (senderNumber.isNullOrEmpty()) return false

        val contentResolver: ContentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
        val selectionArgs = arrayOf(senderNumber)

        val cursor: Cursor? = contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            null
        )

        return cursor?.use {
            it.count > 0
        } ?: false
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val bundle: Bundle? = intent.extras
            Log.d("Message Receiver", "Received message")
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>
                for (pdu in pdus) {
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                    val messageBody = smsMessage.messageBody
                    val senderNumber = smsMessage.displayOriginatingAddress
                    // TODO: preference should be requested
//                    if (!isSenderInContacts(context, senderNumber)) {
                    forwardMessage(context, senderNumber, messageBody)
//                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error in onReceive: ${e.message}")
        }
    }

    private fun forwardMessage(context: Context, senderNumber: String?, messageBody: String) {
        val userPreferences = UserPreferences(context.dataStore)

        CoroutineScope(Dispatchers.IO).launch {
            if (userPreferences.isSmsServiceEnabled.first()) {
                val smsToNumber = userPreferences.smsToNumber.first()
                if (!smsToNumber.isNullOrEmpty()) {
                    sendSms(context, smsToNumber, senderNumber.toString(), messageBody)
                }
            }

            if (userPreferences.isTelegramServiceEnabled.first()) {
                val telegramApiKey = userPreferences.telegramApiKey.first()
                val telegramUserIds = userPreferences.telegramUserIds.first()
                if (!telegramApiKey.isNullOrEmpty() && !telegramUserIds.isNullOrEmpty()) {
                    sendTelegramMessage(telegramApiKey, telegramUserIds, senderNumber.toString(), messageBody)
                }
            }
        }
    }

    private fun sendSms(context: Context, to: String, from: String, message: String) {
        try {
            Log.d("SMS activity", "sending to $to message => $message")
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

            // Send SMS
            val content = "Message from ${from}:\n${message}"
            smsManager.sendTextMessage(to, null, content, sentIntent, deliveredIntent)
        } catch (e: Exception) {
            Log.e("SmsSender", "Failed to send SMS: ${e.message}")
        }
    }

    fun sendTelegramMessage(apiKey: String, userIds: String, from: String, message: String) {
        Log.d("Telegram activity", "sending to $userIds message => $message")
        val baseUrl = "https://api.telegram.org/bot$apiKey/sendMessage"
        val content = "Message from ${from}:\n${message}"

        userIds.split(",").forEach { userId ->
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection

            try {
                // Configure the connection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                // Prepare the message payload
                val payload = "chat_id=$userId&text=${content.replace(" ", "%20")}"
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                // Get the response code
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d("TelegramAPI", "Message sent to $userId")
                } else {
                    Log.e("TelegramAPI", "Failed to send message to $userId: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e("TelegramAPI", "Error sending message to $userId: ${e.message}")
            } finally {
                connection.disconnect()
            }
        }
    }

}
