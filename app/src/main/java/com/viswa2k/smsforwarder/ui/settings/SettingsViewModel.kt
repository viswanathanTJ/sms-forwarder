package com.viswa2k.smsforwarder.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.classify.SenderIds
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.CheckResult
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.DetectedChat
import com.viswa2k.smsforwarder.core.Imported
import com.viswa2k.smsforwarder.core.MessageCheck
import com.viswa2k.smsforwarder.core.PinLock
import com.viswa2k.smsforwarder.core.SendResult
import com.viswa2k.smsforwarder.core.SettingsExport
import com.viswa2k.smsforwarder.pipeline.ContactLookup
import com.viswa2k.smsforwarder.service.HealthChecker
import com.viswa2k.smsforwarder.service.HealthItem
import com.viswa2k.smsforwarder.update.UpdateInfo
import com.viswa2k.smsforwarder.update.UpdateManager
import com.viswa2k.smsforwarder.ui.rules.OneShot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SettingsState(
    val hasToken: Boolean = false,
    val telegram: List<Destination> = emptyList(),
    val sms: List<Destination> = emptyList(),
    val alertsDestinationId: Long? = null,
    val deviceName: String = "",
    val pinSet: Boolean = false,
    val oemConfirmed: Boolean = false,
    val loaded: Boolean = false,
)

/** An active SIM for the SMS-destination picker. */
data class SimOption(val subscriptionId: Int, val label: String)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data object Failed : UpdateStatus
}

/** Result of "Detect": chats that wrote to the bot, or a user-readable error. */
sealed interface DetectState {
    data object Idle : DetectState
    data object Loading : DetectState
    data class Found(val chats: List<DetectedChat>) : DetectState
    data class Error(val message: String) : DetectState
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val settings = graph.settings

    val state: StateFlow<SettingsState> = combine(
        settings.encryptedToken.map { it != null },
        graph.destinations.destinations,
        combine(settings.alertsDestinationId, settings.deviceName, settings.pinHash) { a, d, p -> Triple(a, d, p != null) },
        settings.setupConfirmedOem,
    ) { hasToken, dests, (alerts, device, pin), oem ->
        SettingsState(
            hasToken = hasToken,
            telegram = dests.filter { it.channel == Channel.TELEGRAM },
            sms = dests.filter { it.channel == Channel.SMS },
            alertsDestinationId = alerts,
            deviceName = device,
            pinSet = pin,
            oemConfirmed = oem,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** One-line feedback for a toast (test results, save confirmations, errors). */
    private val _messages = EventChannel<String>(EventChannel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private fun say(text: String) { _messages.trySend(text) }

    // ---- Health ---------------------------------------------------------------------------

    private val healthChecker = HealthChecker(graph.appContext)
    private val _health = MutableStateFlow<List<HealthItem>>(emptyList())
    val health: StateFlow<List<HealthItem>> = _health

    fun refreshHealth() {
        viewModelScope.launch { _health.value = withContext(Dispatchers.Default) { healthChecker.items() } }
    }

    fun setOemConfirmed(done: Boolean) {
        graph.appScope.launch {
            settings.setSetupConfirmedOem(done)
            _health.value = withContext(Dispatchers.Default) { healthChecker.items() }
        }
    }

    // ---- Telegram -------------------------------------------------------------------------

    /** Encrypts and stores [raw]; the token is never read back into the UI. False when the format is wrong. */
    fun saveToken(raw: String): Boolean {
        val token = raw.trim()
        if (!TOKEN_FORMAT.matches(token)) return false
        graph.appScope.launch {
            writeLock.withLock {
                try {
                    val encrypted = withContext(Dispatchers.Default) { graph.tokenCipher.encrypt(token) }
                    settings.setEncryptedToken(encrypted)
                    say("Bot token saved")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("Could not save the token (${e.javaClass.simpleName})")
                }
            }
        }
        return true
    }

    private val _detect = MutableStateFlow<DetectState>(DetectState.Idle)
    val detect: StateFlow<DetectState> = _detect

    fun resetDetect() { _detect.value = DetectState.Idle }

    fun detectChats() {
        if (_detect.value == DetectState.Loading) return
        _detect.value = DetectState.Loading
        viewModelScope.launch {
            val result = graph.telegram.detectChats()
            _detect.value = result.fold(
                onSuccess = { if (it.isEmpty()) DetectState.Error(NO_UPDATES) else DetectState.Found(it) },
                onFailure = { DetectState.Error(detectError(it.message)) },
            )
        }
    }

    private fun detectError(message: String?): String = when {
        message.isNullOrBlank() -> "Could not reach Telegram"
        message == "Bot token not set" -> message
        message.contains("Unauthorized", ignoreCase = true) || message.contains("Not Found", ignoreCase = true) ->
            "Telegram rejected the bot token — check it ($message)"
        message.contains("Conflict", ignoreCase = true) -> "The bot has a webhook set; remove it to use Detect ($message)"
        else -> "Telegram: $message"
    }

    fun addDestination(d: Destination) {
        graph.appScope.launch { writeLock.withLock { graph.destinations.save(d) } }
    }

    /** Removes the destination from every rule too; resets the alerts chat when it was this one. */
    fun deleteDestination(id: Long) {
        graph.appScope.launch {
            writeLock.withLock {
                graph.destinations.delete(id)
                if (settings.alertsDestinationId.first() == id) settings.setAlertsDestinationId(null)
            }
        }
    }

    fun setAlertsDestination(id: Long?) {
        graph.appScope.launch { writeLock.withLock { settings.setAlertsDestinationId(id) } }
    }

    private val testing = MutableStateFlow<Set<Long>>(emptySet())
    /** Destination ids whose Test is in flight (button disabled). */
    val testingIds: StateFlow<Set<Long>> = testing

    /** Sends "✅ Test from <device>"; an SMS test waits for the radio's confirmation (up to 60 s). */
    fun test(d: Destination) {
        if (d.id in testing.value) return
        testing.value = testing.value + d.id
        graph.appScope.launch {
            try {
                val device = deviceLabel()
                val result = when (d.channel) {
                    Channel.TELEGRAM -> graph.telegram.test(d.address, d.topicId, device)
                    Channel.SMS -> graph.smsSender.send(d.address, "✅ Test from $device", d.simSubscriptionId)
                }
                say(
                    when (result) {
                        SendResult.Sent -> "Test sent to ${d.label} ✓"
                        is SendResult.Retry -> "Test to ${d.label} not sent: ${result.reason}"
                        is SendResult.Fatal -> "Test to ${d.label} failed: ${result.reason}"
                    },
                )
            } finally {
                testing.value = testing.value - d.id
            }
        }
    }

    // ---- SMS SIM picker ----------------------------------------------------------------------

    private val _sims = MutableStateFlow<List<SimOption>>(emptyList())
    /** Active SIMs; empty when READ_PHONE_STATE is denied (the picker then shows "Default SIM" only). */
    val sims: StateFlow<List<SimOption>> = _sims

    @SuppressLint("MissingPermission")
    fun loadSims() {
        val context = graph.appContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            _sims.value = emptyList()
            return
        }
        viewModelScope.launch {
            _sims.value = withContext(Dispatchers.IO) {
                try {
                    val sm = context.getSystemService(SubscriptionManager::class.java)
                    sm?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.map {
                        val carrier = it.displayName?.toString()?.takeIf { n -> n.isNotBlank() }
                        SimOption(it.subscriptionId, "SIM ${it.simSlotIndex + 1}" + (carrier?.let { c -> " · $c" } ?: ""))
                    }
                } catch (e: SecurityException) {
                    emptyList()
                }
            }
        }
    }

    // ---- Test a message ------------------------------------------------------------------------

    private val _check = MutableStateFlow<CheckResult?>(null)
    val check: StateFlow<CheckResult?> = _check
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** Real analyzer, rules and formatter, in memory: nothing is written or sent. */
    fun runCheck(sender: String, body: String) {
        if (sender.isBlank() || body.isBlank() || _checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                _check.value = withContext(Dispatchers.IO) {
                    val kind = ContactLookup(graph.appContext).kind(sender.trim(), SenderIds.parse(sender.trim()))
                    MessageCheck.run(
                        sender.trim(), body, kind, graph.analyzer, graph.rules.all(), graph.destinations.all(),
                        deviceLabel(), System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Check failed (${e.javaClass.simpleName})")
            } finally {
                _checking.value = false
            }
        }
    }

    // ---- Device & PIN -------------------------------------------------------------------------

    fun setDeviceName(name: String) {
        graph.appScope.launch { writeLock.withLock { settings.setDeviceName(name.trim()) } }
        say("Device name saved")
    }

    /** The stored PIN record (salt:hash), for verifying entries; null when no PIN is set. */
    val pinRecord: StateFlow<String?> = settings.pinHash.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setPin(pin: String) {
        if (!PinLock.isValid(pin)) return
        graph.appScope.launch {
            writeLock.withLock { settings.setPinHash(withContext(Dispatchers.Default) { PinLock.newRecord(pin) }) }
            say("PIN set")
        }
    }

    fun removePin() {
        graph.appScope.launch {
            writeLock.withLock { settings.setPinHash(null) }
            say("PIN removed")
        }
    }

    // ---- Export / import ----------------------------------------------------------------------

    fun export(uri: Uri, includeToken: Boolean) {
        val context = graph.appContext
        graph.appScope.launch {
            try {
                val token = if (includeToken) {
                    settings.encryptedToken.first()?.let { graph.tokenCipher.decrypt(it) }
                } else null
                val json = SettingsExport.toJson(graph.rules.all(), graph.destinations.all(), settings.deviceName.first(), token)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("cannot open file")
                }
                say(if (includeToken && token == null) "Settings exported (no bot token saved)" else "Settings exported")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Export failed (${e.javaClass.simpleName})")
            }
        }
    }

    private val _pendingImport = MutableStateFlow<Imported?>(null)
    /** A parsed file awaiting the user's confirmation. */
    val pendingImport: StateFlow<Imported?> = _pendingImport

    fun readImport(uri: Uri) {
        val context = graph.appContext
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                        require(bytes.size <= MAX_IMPORT_BYTES) { "File too large" }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("cannot open file")
                }
                importOnce = OneShot()
                _pendingImport.value = withContext(Dispatchers.Default) { SettingsExport.fromJson(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                say(e.message ?: "Not a valid settings file")
            } catch (e: Exception) {
                say("Import failed (${e.javaClass.simpleName})")
            }
        }
    }

    fun cancelImport() { _pendingImport.value = null }

    private var importOnce = OneShot()

    /** Replaces rules + destinations in one transaction; the token only when [includeToken] and present. */
    fun applyImport(includeToken: Boolean) {
        val imported = _pendingImport.value ?: return
        if (!importOnce.tryRun()) return
        _pendingImport.value = null
        graph.appScope.launch {
            try {
                writeLock.withLock {
                    graph.destinations.replaceAll(imported.destinations, imported.rules)
                    settings.setAlertsDestinationId(null) // ids changed; default = first chat
                    val token = imported.token
                    if (includeToken && token != null) {
                        settings.setEncryptedToken(withContext(Dispatchers.Default) { graph.tokenCipher.encrypt(token) })
                    }
                    if (settings.deviceName.first().isBlank() && imported.deviceName.isNotBlank()) {
                        settings.setDeviceName(imported.deviceName)
                    }
                }
                say("Imported ${imported.rules.size} rules and ${imported.destinations.size} destinations")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Import failed (${e.javaClass.simpleName})")
            }
        }
    }

    // ---- Update ---------------------------------------------------------------------------

    private val _update = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val update: StateFlow<UpdateStatus> = _update

    fun checkForUpdate() {
        if (_update.value == UpdateStatus.Checking) return
        _update.value = UpdateStatus.Checking
        viewModelScope.launch {
            _update.value = try {
                UpdateManager.refresh(settings)
                UpdateManager.available(settings).first()?.let { UpdateStatus.Available(it) } ?: UpdateStatus.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateStatus.Failed
            }
        }
    }

    fun dismissUpdate() { _update.value = UpdateStatus.Idle }

    private suspend fun deviceLabel(): String = settings.deviceName.first().ifBlank { Build.MODEL }

    companion object {
        /** `<bot id>:<secret>` as issued by @BotFather. */
        val TOKEN_FORMAT = Regex("^[0-9]{3,}:[A-Za-z0-9_-]{20,}$")
        const val NO_UPDATES = "No messages yet — send /start to your bot, then tap Detect again"
        private const val MAX_IMPORT_BYTES = 1_000_000

        // Shared by every Settings instance: writes apply in order.
        private val writeLock = Mutex()

        fun factory(graph: AppGraph) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(graph) as T
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
