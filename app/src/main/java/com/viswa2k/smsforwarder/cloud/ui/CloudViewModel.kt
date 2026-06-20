package com.viswa2k.smsforwarder.cloud.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.cloud.data.AccessRepository
import com.viswa2k.smsforwarder.cloud.data.AuthRepository
import com.viswa2k.smsforwarder.cloud.data.CloudMessageRepository
import com.viswa2k.smsforwarder.cloud.data.Device
import com.viswa2k.smsforwarder.cloud.data.DeviceRepository
import com.viswa2k.smsforwarder.cloud.data.FirebaseProvider
import com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CloudViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UserPreferences(app.dataStore)
    private val crypto = CryptoManager(app)
    private val auth = AuthRepository()
    private val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)

    private val _signedIn = MutableStateFlow(auth.currentEmail() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin
    private val _email = MutableStateFlow(auth.currentEmail())
    val email: StateFlow<String?> = _email
    private val _messages = MutableStateFlow<List<CloudMessageRepository.DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<CloudMessageRepository.DecryptedMessage>> = _messages

    private var registration: ListenerRegistration? = null
    private var aliasCache: Map<String, String> = emptyMap()

    init { viewModelScope.launch { auth.authState.collect { _signedIn.value = it; _email.value = auth.currentEmail() } } }

    fun signInEmail(email: String, password: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInEmail(email, password) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Sign-in failed") }
    }

    fun signInGoogle(idToken: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInGoogle(idToken) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Google sign-in failed") }
    }

    fun onAuthenticated(onError: (String) -> Unit = {}) = viewModelScope.launch { onAuthenticatedInternal(onError) }

    private suspend fun onAuthenticatedInternal(onError: (String) -> Unit) {
        if (!auth.isAuthorized()) { auth.signOut(); _signedIn.value = false; onError("This email is not authorized."); return }
        val email = auth.currentEmail() ?: return
        var alias = prefs.deviceAlias.first(); if (alias.isBlank()) alias = android.os.Build.MODEL
        runCatching { deviceRepo.registerThisDevice(email, alias) }
        _isAdmin.value = runCatching { auth.isAdmin() }.getOrDefault(false)
        runCatching { deviceRepo.updateFcmToken(FirebaseMessaging.getInstance().token.await()) }
        runCatching { SmsCloudUploader(getApplication()).flushQueue() }
        refreshMessages()
    }

    fun signOut() = viewModelScope.launch { runCatching { auth.signOut() }; _signedIn.value = false }

    fun refreshMessages() = viewModelScope.launch {
        val readerId = prefs.cloudDeviceId.first(); if (readerId.isBlank()) return@launch
        aliasCache = runCatching { deviceRepo.fetchFleetDevices().associate { it.id to it.alias } }.getOrDefault(emptyMap())
        _messages.value = runCatching { messageRepo.listForReader(readerId, aliasCache) }.getOrDefault(emptyList())
    }

    fun startRealtime() = viewModelScope.launch {
        if (registration != null) return@launch
        val readerId = prefs.cloudDeviceId.first(); if (readerId.isBlank()) return@launch
        registration = FirebaseProvider.db.collection("inbox").document(readerId).collection("messages")
            .addSnapshotListener { _, _ -> refreshMessages() }
    }

    fun stopRealtime() { registration?.remove(); registration = null }

    fun deleteMessage(id: String) = viewModelScope.launch { runCatching { messageRepo.deleteMessage(id) }; refreshMessages() }
    fun deleteAllForSource(sourceDeviceId: String) = viewModelScope.launch { runCatching { messageRepo.deleteAllForSource(sourceDeviceId) }; refreshMessages() }

    suspend fun fleetDevices(): List<Device> = runCatching { deviceRepo.fetchFleetDevices() }.getOrDefault(emptyList())
    suspend fun myDeviceId(): String = prefs.cloudDeviceId.first()
    fun accessRepository() = accessRepo

    override fun onCleared() { stopRealtime() }
}
