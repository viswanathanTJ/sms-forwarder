package com.viswa2k.smsforwarder.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val userPreferences: UserPreferences) : ViewModel() {
    private val _smsForwardServiceEnabled = MutableStateFlow(false)
    val smsForwardServiceEnabled: StateFlow<Boolean> = _smsForwardServiceEnabled

    private val _isSkipContacts = MutableStateFlow(false)
    val isSkipContacts: StateFlow<Boolean> = _isSkipContacts

    private val _isBySmsEnabled = MutableStateFlow(false)
    val isBySmsEnabled: StateFlow<Boolean> = _isBySmsEnabled

    private val _isByTelegramEnabled = MutableStateFlow(false)
    val isByTelegramEnabled: StateFlow<Boolean> = _isByTelegramEnabled

    private val _deviceAlias = MutableStateFlow("")
    val deviceAlias: StateFlow<String> = _deviceAlias

    private val _globalMessageFormat = MutableStateFlow("")
    val globalMessageFormat: StateFlow<String> = _globalMessageFormat

    private val _smsMessageFormat = MutableStateFlow("")
    val smsMessageFormat: StateFlow<String> = _smsMessageFormat

    private val _telegramMessageFormat = MutableStateFlow("")
    val telegramMessageFormat: StateFlow<String> = _telegramMessageFormat

    private val _smsToNumber = MutableStateFlow("")
    val smsToNumber: StateFlow<String> = _smsToNumber

    private val _telegramApiKey = MutableStateFlow("")
    val telegramApiKey: StateFlow<String> = _telegramApiKey

    private val _telegramUserIds = MutableStateFlow("")
    val telegramUserIds: StateFlow<String> = _telegramUserIds

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            _smsForwardServiceEnabled.value = userPreferences.isSmsForwarderService.first()
            _isSkipContacts.value = userPreferences.isSkipContacts.first()
            _isBySmsEnabled.value = userPreferences.isBySmsEnabled.first()
            _isByTelegramEnabled.value = userPreferences.isByTelegramEnabled.first()
            _deviceAlias.value = userPreferences.deviceAlias.first()
            _globalMessageFormat.value = userPreferences.globalMessageFormat.first()
            _smsMessageFormat.value = userPreferences.smsForwardMessageFormat.first()
            _telegramMessageFormat.value = userPreferences.telegramSendMessageFormat.first()
            _smsToNumber.value = userPreferences.smsToNumber.first()
            _telegramApiKey.value = userPreferences.telegramApiKey.first()
            _telegramUserIds.value = userPreferences.telegramUserIds.first()
        }
    }

    // Update methods for all state variables
    fun updateSmsForwardServiceEnabled(enabled: Boolean) {
        _smsForwardServiceEnabled.value = enabled
    }

    fun updateIsSkipContacts(enabled: Boolean) {
        _isSkipContacts.value = enabled
    }

    fun updateIsBySmsEnabled(enabled: Boolean) {
        _isBySmsEnabled.value = enabled
    }

    fun updateIsByTelegramEnabled(enabled: Boolean) {
        _isByTelegramEnabled.value = enabled
    }

    fun updateDeviceAlias(alias: String) {
        _deviceAlias.value = alias
    }

    fun updateGlobalMessageFormat(format: String) {
        _globalMessageFormat.value = format
        if (_smsMessageFormat.value.isEmpty()) _smsMessageFormat.value = format
        if (_telegramMessageFormat.value.isEmpty()) _telegramMessageFormat.value = format
    }

    fun updateSmsMessageFormat(format: String) {
        _smsMessageFormat.value = format
    }

    fun updateTelegramMessageFormat(format: String) {
        _telegramMessageFormat.value = format
    }

    fun updateSmsToNumber(number: String) {
        _smsToNumber.value = number.take(15) // Limit to 15 characters
    }

    fun updateTelegramApiKey(apiKey: String) {
        _telegramApiKey.value = apiKey
    }

    fun updateTelegramUserIds(userIds: String) {
        _telegramUserIds.value = userIds
    }

    fun saveSettings() {
        viewModelScope.launch {
            userPreferences.saveSmsServiceEnabled(_smsForwardServiceEnabled.value)
            userPreferences.saveIsSkipContacts(_isSkipContacts.value)
            userPreferences.saveDeviceAlias(_deviceAlias.value)

            userPreferences.saveBySmsEnabled(_isBySmsEnabled.value)
            userPreferences.saveByTelegramEnabled(_isByTelegramEnabled.value)

            userPreferences.saveGlobalMessageFormat(_globalMessageFormat.value)
            userPreferences.saveSmsForwardFormat(_smsMessageFormat.value)
            userPreferences.saveTelegramSendFormat(_telegramMessageFormat.value)
            userPreferences.saveSmsToNumber(_smsToNumber.value)
            userPreferences.saveTelegramApiKey(_telegramApiKey.value)
            userPreferences.saveTelegramUserIds(_telegramUserIds.value)
        }
    }
}
