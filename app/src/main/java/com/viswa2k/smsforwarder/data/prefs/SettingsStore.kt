package com.viswa2k.smsforwarder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.viswa2k.smsforwarder.core.MessageType
import com.viswa2k.smsforwarder.core.SourceFilter
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// Distinct from v1's "settings" store (V1MigrationRunner.dataStore); never reuse that name.
// A corrupt file is replaced instead of crashing every reader: migration counts as done (a corrupt v2 store
// must not re-import or wipe v1 leftovers) and forwarding starts PAUSED so the owner notices and re-checks.
private val Context.settingsV2: DataStore<Preferences> by preferencesDataStore(
    name = "settings_v2",
    corruptionHandler = ReplaceFileCorruptionHandler {
        preferencesOf(booleanPreferencesKey(SettingsStore.MIGRATION_DONE) to true, booleanPreferencesKey(SettingsStore.MASTER_ON) to false)
    },
)

/** WhatsApp source toggle + [floor] = when it was last switched on (messages before it are never forwarded). */
data class WhatsAppPrefs(val enabled: Boolean, val floor: Long)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsV2

    private val masterOnKey = booleanPreferencesKey(MASTER_ON)
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val tokenKey = stringPreferencesKey("encrypted_token")
    private val pinKey = stringPreferencesKey("pin_hash")
    private val alertsDestKey = longPreferencesKey("alerts_destination_id")
    private val floorKey = longPreferencesKey("catch_up_floor")
    private val migrationKey = booleanPreferencesKey(MIGRATION_DONE)
    private val oemKey = booleanPreferencesKey("setup_confirmed_oem")
    private val pendingRebootKey = longPreferencesKey("pending_reboot_at")
    private val alertStateKey = stringPreferencesKey("alert_state")
    private val storedUpdateKey = stringPreferencesKey("stored_update")
    private val onboardingKey = booleanPreferencesKey("onboarding_done")
    private val pinAttemptsKey = stringPreferencesKey("pin_attempts")
    private val whatsAppKey = booleanPreferencesKey("whatsapp_enabled")
    private val waTypesKey = stringSetPreferencesKey("whatsapp_types")
    private val waFloorKey = longPreferencesKey("wa_floor")

    val masterOn: Flow<Boolean> = store.data.map { it[masterOnKey] ?: true }
    val deviceName: Flow<String> = store.data.map { it[deviceNameKey] ?: "" }
    val encryptedToken: Flow<String?> = store.data.map { it[tokenKey] }
    /** [com.viswa2k.smsforwarder.core.PinLock] record `salt:hash`, or null when no PIN is set. */
    val pinHash: Flow<String?> = store.data.map { it[pinKey] }
    val alertsDestinationId: Flow<Long?> = store.data.map { it[alertsDestKey] }
    val catchUpFloor: Flow<Long> = store.data.map { it[floorKey] ?: 0L }
    val migrationDone: Flow<Boolean> = store.data.map { it[migrationKey] ?: false }
    val setupConfirmedOem: Flow<Boolean> = store.data.map { it[oemKey] ?: false }
    /** Boot time (epoch ms) whose "phone restarted" alert has not been delivered yet; null when none. */
    val pendingReboot: Flow<Long?> = store.data.map { it[pendingRebootKey] }
    /** JSON object: problemKey -> lastSentAt (epoch ms). */
    val alertState: Flow<String> = store.data.map { it[alertStateKey] ?: "{}" }

    /** Last-known newer release (JSON from update/UpdateChecker) or "". */
    val storedUpdate: Flow<String> = store.data.map { it[storedUpdateKey] ?: "" }
    /** First-run setup finished once; onboarding still returns while a required permission is missing. */
    val onboardingDone: Flow<Boolean> = store.data.map { it[onboardingKey] ?: false }
    /** [com.viswa2k.smsforwarder.core.PinBackoff] state, "failures:lockedUntil". */
    val pinAttempts: Flow<String> = store.data.map { it[pinAttemptsKey] ?: "" }

    /** WhatsApp notification source; off by default. */
    val whatsAppEnabled: Flow<Boolean> = store.data.map { it[whatsAppKey] ?: false }
    /** Types forwarded from WhatsApp (codes stored); default only OTP, never empty, never SPAM. */
    val whatsAppTypes: Flow<Set<MessageType>> = store.data.map { SourceFilter.fromCodes(it[waTypesKey]) }
    val whatsApp: Flow<WhatsAppPrefs> = store.data.map { WhatsAppPrefs(it[whatsAppKey] ?: false, it[waFloorKey] ?: 0L) }

    suspend fun setMasterOn(on: Boolean) { store.edit { it[masterOnKey] = on } }
    suspend fun setDeviceName(name: String) { store.edit { it[deviceNameKey] = name } }
    suspend fun setEncryptedToken(token: String?) { store.edit { if (token == null) it.remove(tokenKey) else it[tokenKey] = token } }
    /** Setting or removing the PIN also clears the wrong-PIN back-off. */
    suspend fun setPinHash(hash: String?) {
        store.edit {
            if (hash == null) it.remove(pinKey) else it[pinKey] = hash
            it.remove(pinAttemptsKey)
        }
    }
    suspend fun setAlertsDestinationId(id: Long?) { store.edit { if (id == null) it.remove(alertsDestKey) else it[alertsDestKey] = id } }
    suspend fun setCatchUpFloor(epochMs: Long) { store.edit { it[floorKey] = epochMs } }
    /**
     * Log "Clear": the deleted rows were the dedupe keys of SMS and WhatsApp alike, so both floors move in
     * one edit ([waFloor] only; the WhatsApp toggle is untouched).
     */
    suspend fun setFloorsAfterClear(catchUpFloor: Long, waFloor: Long) {
        store.edit {
            it[floorKey] = catchUpFloor
            it[waFloorKey] = waFloor
        }
    }
    suspend fun setMigrationDone(done: Boolean) { store.edit { it[migrationKey] = done } }
    suspend fun setSetupConfirmedOem(done: Boolean) { store.edit { it[oemKey] = done } }
    suspend fun setPendingReboot(at: Long?) { store.edit { if (at == null) it.remove(pendingRebootKey) else it[pendingRebootKey] = at } }
    suspend fun setAlertState(json: String) { store.edit { it[alertStateKey] = json } }
    suspend fun setStoredUpdate(json: String) { store.edit { it[storedUpdateKey] = json } }
    suspend fun setOnboardingDone(done: Boolean) { store.edit { it[onboardingKey] = done } }
    suspend fun setPinAttempts(state: String) { store.edit { it[pinAttemptsKey] = state } }
    /** Ignored when [types] has nothing selectable (at least one type must stay on). */
    suspend fun setWhatsAppTypes(types: Set<MessageType>) {
        val clean = types.filter { it != MessageType.SPAM }
        if (clean.isEmpty()) return
        store.edit { it[waTypesKey] = clean.map { t -> t.code.toString() }.toSet() }
    }
    /** Switching on also moves the WhatsApp floor to [now] (one edit); switching off keeps the floor. */
    suspend fun setWhatsAppEnabled(on: Boolean, now: Long) {
        store.edit {
            if (on) it[waFloorKey] = now
            it[whatsAppKey] = on
        }
    }

    companion object {
        internal const val MASTER_ON = "master_on"
        internal const val MIGRATION_DONE = "migration_done"

        fun parseAlertState(json: String): Map<String, Long> = try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) {
            emptyMap()
        }

        fun encodeAlertState(state: Map<String, Long>): String =
            JSONObject().also { o -> state.forEach { (k, v) -> o.put(k, v) } }.toString()
    }
}
