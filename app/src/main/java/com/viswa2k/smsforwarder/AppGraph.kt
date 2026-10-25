package com.viswa2k.smsforwarder

import android.content.Context
import android.util.Log
import com.viswa2k.smsforwarder.classify.KeywordLists
import com.viswa2k.smsforwarder.core.MessageAnalyzer
import com.viswa2k.smsforwarder.data.db.ForwarderDatabase
import com.viswa2k.smsforwarder.data.prefs.SettingsStore
import com.viswa2k.smsforwarder.data.prefs.TokenCipher
import com.viswa2k.smsforwarder.data.repo.AssetKeywordSource
import com.viswa2k.smsforwarder.data.repo.DestinationRepository
import com.viswa2k.smsforwarder.data.repo.LogRepository
import com.viswa2k.smsforwarder.data.repo.OutboxRepository
import com.viswa2k.smsforwarder.data.repo.RuleRepository
import com.viswa2k.smsforwarder.delivery.Dispatcher
import com.viswa2k.smsforwarder.delivery.SmsSender
import com.viswa2k.smsforwarder.delivery.TelegramClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/** Hand-rolled DI: everything is `by lazy` so a receiver wake-up only builds what it touches. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Completed by [com.viswa2k.smsforwarder.data.prefs.V1MigrationRunner] when first-run migration ends. */
    val migrationGate = CompletableDeferred<Unit>()
    val database by lazy { ForwarderDatabase.create(appContext) }
    val settings by lazy { SettingsStore(appContext) }
    val tokenCipher by lazy { TokenCipher(appContext) }
    val rules by lazy { RuleRepository(database.rules()) }
    val destinations by lazy { DestinationRepository(database) }
    val logs by lazy { LogRepository(database.logs()) }
    val outbox by lazy { OutboxRepository(database.outbox()) }

    val telegram by lazy {
        TelegramClient { settings.encryptedToken.first()?.let { tokenCipher.decrypt(it) } }
    }
    val smsSender by lazy { SmsSender(appContext) }
    val dispatcher by lazy { Dispatcher(this) }

    /** Keyword lists load once; a failure leaves the analyzer in its reduced (null lists) mode. */
    val analyzer: MessageAnalyzer by lazy {
        val lists = try {
            KeywordLists.load(AssetKeywordSource(appContext))
        } catch (e: Exception) {
            Log.w("AppGraph", "keyword lists unavailable", e)
            null
        }
        MessageAnalyzer(lists)
    }

    /** False when the keyword lists failed to load (health check + problem alert). */
    val classifierLoaded: Boolean get() = !analyzer.reduced

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ForwarderApp).graph
    }
}
