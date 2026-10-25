package com.viswa2k.smsforwarder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// Distinct from v1's "settings" store (V1MigrationRunner.dataStore); never reuse that name.
private val Context.settingsV2: DataStore<Preferences> by preferencesDataStore(name = "settings_v2")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsV2

    private val masterOnKey = booleanPreferencesKey("master_on")
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val tokenKey = stringPreferencesKey("encrypted_token")
    private val pinKey = stringPreferencesKey("pin_hash")
    private val alertsDestKey = longPreferencesKey("alerts_destination_id")
    private val floorKey = longPreferencesKey("catch_up_floor")
    private val migrationKey = booleanPreferencesKey("migration_done")
    private val oemKey = booleanPreferencesKey("setup_confirmed_oem")
    private val pendingRebootKey = longPreferencesKey("pending_reboot_at")
    private val alertStateKey = stringPreferencesKey("alert_state")
    private val storedUpdateKey = stringPreferencesKey("stored_update")
    private val onboardingKey = booleanPreferencesKey("onboarding_done")

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

    suspend fun setMasterOn(on: Boolean) { store.edit { it[masterOnKey] = on } }
    suspend fun setDeviceName(name: String) { store.edit { it[deviceNameKey] = name } }
    suspend fun setEncryptedToken(token: String?) { store.edit { if (token == null) it.remove(tokenKey) else it[tokenKey] = token } }
    suspend fun setPinHash(hash: String?) { store.edit { if (hash == null) it.remove(pinKey) else it[pinKey] = hash } }
    suspend fun setAlertsDestinationId(id: Long?) { store.edit { if (id == null) it.remove(alertsDestKey) else it[alertsDestKey] = id } }
    suspend fun setCatchUpFloor(epochMs: Long) { store.edit { it[floorKey] = epochMs } }
    suspend fun setMigrationDone(done: Boolean) { store.edit { it[migrationKey] = done } }
    suspend fun setSetupConfirmedOem(done: Boolean) { store.edit { it[oemKey] = done } }
    suspend fun setPendingReboot(at: Long?) { store.edit { if (at == null) it.remove(pendingRebootKey) else it[pendingRebootKey] = at } }
    suspend fun setAlertState(json: String) { store.edit { it[alertStateKey] = json } }
    suspend fun setStoredUpdate(json: String) { store.edit { it[storedUpdateKey] = json } }
    suspend fun setOnboardingDone(done: Boolean) { store.edit { it[onboardingKey] = done } }

    companion object {
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
