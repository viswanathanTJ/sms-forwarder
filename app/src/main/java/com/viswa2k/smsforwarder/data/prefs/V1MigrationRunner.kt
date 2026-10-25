package com.viswa2k.smsforwarder.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.DefaultRules
import com.viswa2k.smsforwarder.core.V1Migration
import com.viswa2k.smsforwarder.core.V1Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

// The v1 "settings" store; its only reader is this migration. Exactly one delegate for this name may exist.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * One-time import of the v1 settings (spec 5.10). Idempotent via `migrationDone`; never throws: on
 * failure only the exception class is logged and the next launch retries. The gate in [AppGraph] opens
 * either way so receivers/workers never hang; [awaitDone] is what startup readers wait on.
 */
object V1MigrationRunner {
    private const val TAG = "V1Migration"

    private val serviceOn = booleanPreferencesKey("SMS_FORWARD_SERVICE")
    private val skipContacts = booleanPreferencesKey("IS_SKIP_CONTACTS")
    private val bySms = booleanPreferencesKey("IS_BY_SMS_ENABLED")
    private val byTelegram = booleanPreferencesKey("IS_BY_TELEGRAM_ENABLED")
    private val alias = stringPreferencesKey("DEVICE_ALIAS")
    private val smsTo = stringPreferencesKey("SMS_TO_NUMBER")
    private val token = stringPreferencesKey("TELEGRAM_API_KEY")
    private val userIds = stringPreferencesKey("TELEGRAM_USER_IDS")

    /** Suspends until the first-run migration has finished (or failed and will retry next launch). */
    suspend fun awaitDone(graph: AppGraph) = graph.migrationGate.await()

    suspend fun runOnce(graph: AppGraph) {
        try {
            migrate(graph)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "migration failed, will retry: ${e.javaClass.simpleName}")
        } finally {
            graph.migrationGate.complete(Unit)
        }
    }

    private suspend fun migrate(graph: AppGraph) {
        val done = graph.settings.migrationDone.first()
        if (done) return
        val prefs = graph.appContext.dataStore.data.first()
        val known = listOf(serviceOn, skipContacts, bySms, byTelegram, alias, smsTo, token, userIds)
        val v1 = if (known.none { it in prefs }) null else V1Settings(
            token = prefs[token].orEmpty(),
            chatIds = V1Migration.splitChats(prefs[userIds].orEmpty()),
            smsNumber = prefs[smsTo].orEmpty(),
            deviceAlias = prefs[alias].orEmpty(),
            serviceOn = prefs[serviceOn] ?: false,
            telegramOn = prefs[byTelegram] ?: false,
            smsOn = prefs[bySms] ?: false,
            skipContacts = prefs[skipContacts] ?: false,
        )
        val plan = V1Migration.plan(v1)

        val steps = V1Migration.steps(done, graph.rules.all().isNotEmpty(), v1 != null)
        if (steps.importDb) {
            graph.database.withTransaction {
                val ids = plan.destinations.map { graph.destinations.save(it) }
                DefaultRules.create(plan.assignToRules.map { ids[it] }, plan.sender)
                    .forEach { graph.rules.save(it) }
            }
        }
        if (steps.writeSettings) {
            plan.token?.let { graph.settings.setEncryptedToken(graph.tokenCipher.encrypt(it)) }
            graph.settings.setDeviceName(plan.deviceName)
            graph.settings.setMasterOn(plan.masterOn)
            graph.settings.setCatchUpFloor(System.currentTimeMillis())
        }
        // Flag BEFORE the destructive cleanup: a crash after clearing v1 keys must not look like a fresh install.
        if (steps.markDone) graph.settings.setMigrationDone(true)
        if (steps.cleanup) {
            try {
                File(graph.appContext.filesDir, "cloud_upload_queue").deleteRecursively()
                graph.appContext.dataStore.edit { p ->
                    p.clear()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "v1 cleanup failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
