package com.viswa2k.smsforwarder

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserPreferences(private val dataStore: DataStore<Preferences>) {

    // Define keys
    private val SMS_FORWARD_SERVICE = booleanPreferencesKey("SMS_FORWARD_SERVICE")
    private val IS_SKIP_CONTACTS = booleanPreferencesKey("IS_SKIP_CONTACTS")
    private val IS_BY_SMS_ENABLED = booleanPreferencesKey("IS_BY_SMS_ENABLED")
    private val IS_BY_TELEGRAM_ENABLED = booleanPreferencesKey("IS_BY_TELEGRAM_ENABLED")

    private val DEVICE_ALIAS = stringPreferencesKey("DEVICE_ALIAS")
    private val GLOBAL_MESSAGE_FORMAT = stringPreferencesKey("GLOBAL_MESSAGE_FORMAT")
    private val SMS_FORWARD_FORMAT = stringPreferencesKey("SMS_FORWARD_FORMAT")
    private val TELEGRAM_SEND_FORMAT = stringPreferencesKey("TELEGRAM_SEND_FORMAT")

    private val SMS_TO_NUMBER_KEY = stringPreferencesKey("SMS_TO_NUMBER")
    private val TELEGRAM_API_KEY_KEY = stringPreferencesKey("TELEGRAM_API_KEY")
    private val TELEGRAM_USER_IDS_KEY = stringPreferencesKey("TELEGRAM_USER_IDS")

    // Initialize defaults
    suspend fun initializeDefaults() {
        dataStore.edit { preferences ->
            if (preferences[SMS_FORWARD_SERVICE] == null) {
                preferences[SMS_FORWARD_SERVICE] = false
            }
            if (preferences[IS_SKIP_CONTACTS] == null) {
                preferences[IS_SKIP_CONTACTS] = false
            }
            if (preferences[IS_BY_SMS_ENABLED] == null) {
                preferences[IS_BY_SMS_ENABLED] = false
            }
            if (preferences[IS_BY_TELEGRAM_ENABLED] == null) {
                preferences[IS_BY_TELEGRAM_ENABLED] = false
            }
            if (preferences[DEVICE_ALIAS] == null) {
                preferences[DEVICE_ALIAS] = ""
            }
            if (preferences[GLOBAL_MESSAGE_FORMAT] == null) {
                preferences[GLOBAL_MESSAGE_FORMAT] = ""
            }
            if (preferences[SMS_FORWARD_FORMAT] == null) {
                preferences[SMS_FORWARD_FORMAT] = ""
            }
            if (preferences[TELEGRAM_SEND_FORMAT] == null) {
                preferences[TELEGRAM_SEND_FORMAT] = ""
            }
            if (preferences[SMS_TO_NUMBER_KEY] == null) {
                preferences[SMS_TO_NUMBER_KEY] = ""
            }
            if (preferences[TELEGRAM_API_KEY_KEY] == null) {
                preferences[TELEGRAM_API_KEY_KEY] = ""
            }
            if (preferences[TELEGRAM_USER_IDS_KEY] == null) {
                preferences[TELEGRAM_USER_IDS_KEY] = ""
            }
        }
    }

    // Retrieve preferences
    val isSmsForwarderService: Flow<Boolean> = dataStore.data.map { it[SMS_FORWARD_SERVICE] ?: false }
    val isSkipContacts: Flow<Boolean> = dataStore.data.map { it[IS_SKIP_CONTACTS] ?: false }
    val isBySmsEnabled: Flow<Boolean> = dataStore.data.map { it[IS_BY_SMS_ENABLED] ?: false }
    val isByTelegramEnabled: Flow<Boolean> = dataStore.data.map { it[IS_BY_TELEGRAM_ENABLED] ?: false }

    val deviceAlias: Flow<String> = dataStore.data.map { it[DEVICE_ALIAS] ?: "" }
    val globalMessageFormat: Flow<String> = dataStore.data.map { it[GLOBAL_MESSAGE_FORMAT] ?: "" }
    val smsForwardMessageFormat: Flow<String> = dataStore.data.map { it[SMS_FORWARD_FORMAT] ?: "" }
    val telegramSendMessageFormat: Flow<String> = dataStore.data.map { it[TELEGRAM_SEND_FORMAT] ?: "" }

    val smsToNumber: Flow<String> = dataStore.data.map { it[SMS_TO_NUMBER_KEY] ?: "" }
    val telegramApiKey: Flow<String> = dataStore.data.map { it[TELEGRAM_API_KEY_KEY] ?: "" }
    val telegramUserIds: Flow<String> = dataStore.data.map { it[TELEGRAM_USER_IDS_KEY] ?: "" }

    // Save preferences
    suspend fun saveSmsServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SMS_FORWARD_SERVICE] = enabled
        }
    }

    suspend fun saveIsSkipContacts(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[IS_SKIP_CONTACTS] = enabled
        }
    }

    suspend fun saveBySmsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[IS_BY_SMS_ENABLED] = enabled
        }
    }

    suspend fun saveByTelegramEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[IS_BY_TELEGRAM_ENABLED] = enabled
        }
    }

    suspend fun saveSmsToNumber(number: String) {
        Log.d("UserPreferences", "Saving SMS number: $number")
        dataStore.edit { preferences ->
            preferences[SMS_TO_NUMBER_KEY] = number
        }
    }

    suspend fun saveTelegramApiKey(apiKey: String) {
        Log.d("UserPreferences", "Saving Telegram API key: $apiKey")
        dataStore.edit { preferences ->
            preferences[TELEGRAM_API_KEY_KEY] = apiKey
        }
    }

    suspend fun saveTelegramSendFormat(format: String) {
        Log.d("UserPreferences", "Saving Telegram send format: $format")
        dataStore.edit { preferences ->
            preferences[TELEGRAM_SEND_FORMAT] = format
        }
    }

    suspend fun saveSmsForwardFormat(format: String) {
        Log.d("UserPreferences", "Saving SMS forward format: $format")
        dataStore.edit { preferences ->
            preferences[SMS_FORWARD_FORMAT] = format
        }
    }

    suspend fun saveDeviceAlias(alias: String) {
        dataStore.edit { preferences ->
            preferences[DEVICE_ALIAS] = alias
        }
    }

    suspend fun saveGlobalMessageFormat(format: String) {
        Log.d("UserPreferences", "Saving global message format: $format")
        dataStore.edit { preferences ->
            preferences[GLOBAL_MESSAGE_FORMAT] = format
        }
    }

    suspend fun saveTelegramUserIds(userIds: String) {
        Log.d("UserPreferences", "Saving Telegram user IDs: $userIds")
        dataStore.edit { preferences ->
            preferences[TELEGRAM_USER_IDS_KEY] = userIds
        }
    }
}
