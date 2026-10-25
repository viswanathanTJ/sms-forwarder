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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled DI: everything is `by lazy` so a receiver wake-up only builds what it touches. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { ForwarderDatabase.create(appContext) }
    val settings by lazy { SettingsStore(appContext) }
    val tokenCipher by lazy { TokenCipher(appContext) }
    val rules by lazy { RuleRepository(database.rules()) }
    val destinations by lazy { DestinationRepository(database) }
    val logs by lazy { LogRepository(database.logs()) }
    val outbox by lazy { OutboxRepository(database.outbox()) }

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

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ForwarderApp).graph
    }
}
