package com.viswa2k.smsforwarder.data.repo

import androidx.room.withTransaction
import com.viswa2k.smsforwarder.core.DefaultRules
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.data.db.Converters
import com.viswa2k.smsforwarder.data.db.ForwarderDatabase
import com.viswa2k.smsforwarder.data.db.toDestination
import com.viswa2k.smsforwarder.data.db.toEntity
import com.viswa2k.smsforwarder.data.db.toRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DestinationRepository(private val db: ForwarderDatabase) {
    private val dao = db.destinations()
    val destinations: Flow<List<Destination>> = dao.observe().map { list -> list.map { it.toDestination() } }
    suspend fun all(): List<Destination> = dao.all().map { it.toDestination() }
    /**
     * Inserts (`id == 0`) or replaces a destination. Adding the very FIRST destination also attaches it to
     * the default rules that still have none and switches the first three on ([DefaultRules.attachFirstDestination]),
     * so a fresh install forwards as soon as one destination exists.
     */
    suspend fun save(destination: Destination): Long = db.withTransaction {
        val first = destination.id == 0L && dao.all().isEmpty()
        val id = dao.save(destination.toEntity())
        if (first) {
            val rules = db.rules()
            DefaultRules.attachFirstDestination(rules.all().map { it.toRule() }, id).forEach { rules.upsert(it.toEntity()) }
        }
        id
    }

    /** Deletes the destination and removes its id from every rule. */
    suspend fun delete(id: Long) = db.withTransaction {
        val rules = db.rules()
        for (rule in rules.all()) {
            val ids = Converters.csvToIds(rule.destinationIds)
            if (id in ids) rules.upsert(rule.copy(destinationIds = Converters.idsToCsv(ids - id)))
        }
        dao.delete(id)
    }

    /**
     * Settings import: replaces every destination and rule in one transaction. Fresh ids are assigned;
     * [rules] refer to the ids in [destinations] and are remapped (unknown ids dropped), order kept.
     */
    suspend fun replaceAll(destinations: List<Destination>, rules: List<Rule>) = db.withTransaction {
        val ruleDao = db.rules()
        ruleDao.deleteAll()
        dao.deleteAll()
        val newIds = destinations.associate { it.id to dao.insert(it.copy(id = 0).toEntity()) }
        rules.sortedWith(compareBy<Rule> { it.position }.thenBy { it.id }).forEachIndexed { i, r ->
            ruleDao.insert(r.copy(id = 0, position = i, destinationIds = r.destinationIds.mapNotNull { newIds[it] }).toEntity())
        }
    }
}
