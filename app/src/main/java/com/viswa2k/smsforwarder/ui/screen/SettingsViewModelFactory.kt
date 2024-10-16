package com.viswa2k.smsforwarder.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.viswa2k.smsforwarder.UserPreferences

class SettingsViewModelFactory(private val userPreferences: UserPreferences) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(userPreferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
