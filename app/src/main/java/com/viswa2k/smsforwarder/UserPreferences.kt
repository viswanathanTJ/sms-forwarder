package com.viswa2k.smsforwarder

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class UserPreferences(private val dataStore: DataStore<Preferences>) {

    // Define keys
    private val SMS_SERVICE_ENABLED_KEY = booleanPreferencesKey("SMS_SERVICE_ENABLED")
    private val SMS_TO_NUMBER_KEY = stringPreferencesKey("SMS_TO_NUMBER") // Store as string for flexibility
    private val TELEGRAM_SERVICE_ENABLED_KEY = booleanPreferencesKey("TELEGRAM_SERVICE_ENABLED")
    private val MESSAGE_FORMAT = stringPreferencesKey("MESSAGE_FORMAT")
    private val TELEGRAM_API_KEY_KEY = stringPreferencesKey("TELEGRAM_API_KEY")
    private val TELEGRAM_USER_IDS_KEY = stringPreferencesKey("TELEGRAM_USER_IDS")

    // Initialize defaults (you can call this in MainActivity)
    suspend fun initializeDefaults() {
        dataStore.edit { preferences ->
            preferences[SMS_SERVICE_ENABLED_KEY] = preferences[SMS_SERVICE_ENABLED_KEY] ?: false
            preferences[TELEGRAM_SERVICE_ENABLED_KEY] = preferences[TELEGRAM_SERVICE_ENABLED_KEY] ?: false
            preferences[SMS_TO_NUMBER_KEY] = preferences[SMS_TO_NUMBER_KEY] ?: ""
            preferences[TELEGRAM_API_KEY_KEY] = preferences[TELEGRAM_API_KEY_KEY] ?: ""
            preferences[TELEGRAM_USER_IDS_KEY] = preferences[TELEGRAM_USER_IDS_KEY] ?: ""
        }
    }

    // Retrieve preferences
    val isSmsServiceEnabled: Flow<Boolean> = dataStore.data.map { it[SMS_SERVICE_ENABLED_KEY] ?: false }
    val isTelegramServiceEnabled: Flow<Boolean> = dataStore.data.map { it[TELEGRAM_SERVICE_ENABLED_KEY] ?: false }
    val smsToNumber: Flow<String?> = dataStore.data.map { it[SMS_TO_NUMBER_KEY] }
    val telegramApiKey: Flow<String?> = dataStore.data.map { it[TELEGRAM_API_KEY_KEY] }
    val telegramUserIds: Flow<String?> = dataStore.data.map { it[TELEGRAM_USER_IDS_KEY] }

    // Save preferences

    suspend fun saveSmsServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SMS_SERVICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveTelegramServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[TELEGRAM_SERVICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveSmsToNumber(number: String) {
        Log.d("UserPreferences", "Saving SMS number: $number")
        dataStore.edit { preferences ->
            preferences[SMS_TO_NUMBER_KEY] = number
        }
    }

    suspend fun saveTelegramApiKey(apiKey: String) {
        Log.d("UserPreferences", "Saving Telegram api key: $apiKey")
        dataStore.edit { preferences ->
            preferences[TELEGRAM_API_KEY_KEY] = apiKey
        }
    }

    suspend fun saveMessageFormat(messageFormat: String) {
        Log.d("UserPreferences", "Saving message format: $messageFormat")
        dataStore.edit { preferences ->
            preferences[MESSAGE_FORMAT] = messageFormat
        }
    }

    suspend fun saveTelegramUserIds(userIds: String) {
        Log.d("UserPreferences", "Saving Telegram user ids: $userIds")
        dataStore.edit { preferences ->
            preferences[TELEGRAM_USER_IDS_KEY] = userIds
        }
    }

    suspend fun isSmsServiceEnabled(): Boolean {
        return dataStore.data.map { it[SMS_SERVICE_ENABLED_KEY] ?: false }.first()
    }

}

