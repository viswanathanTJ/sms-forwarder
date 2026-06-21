package com.viswa2k.smsforwarder.cloud.update

import com.viswa2k.smsforwarder.BuildConfig
import com.viswa2k.smsforwarder.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for "is an update available?".
 *
 * The last check is persisted in DataStore so the indicator survives restarts
 * without re-checking, and it auto-clears once the installed version catches up
 * to the stored latest. Callers don't poll: [refresh] runs once on launch /
 * when a screen opens, and the UI observes [available].
 */
object UpdateManager {

    /** Check GitHub and persist: store the newer release, or clear it when up to date. */
    suspend fun refresh(prefs: UserPreferences) {
        val info = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
        prefs.saveStoredUpdate(if (info != null) UpdateChecker.serialize(info) else "")
    }

    /**
     * Emits the stored newer release, or null. Re-filtered against the installed
     * version so the prompt disappears automatically after the app updates
     * (installed == latest → no longer "newer" → null).
     */
    fun available(prefs: UserPreferences): Flow<UpdateInfo?> = prefs.storedUpdate.map { stored ->
        UpdateChecker.deserialize(stored)
            ?.takeIf { UpdateChecker.isNewer(it.version, BuildConfig.VERSION_NAME) }
    }
}
