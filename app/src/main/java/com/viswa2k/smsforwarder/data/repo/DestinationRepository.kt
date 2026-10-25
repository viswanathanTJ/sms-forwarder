package com.viswa2k.smsforwarder.data.repo

import androidx.room.withTransaction
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.data.db.Converters
import com.viswa2k.smsforwarder.data.db.ForwarderDatabase
import com.viswa2k.smsforwarder.data.db.toDestination
import com.viswa2k.smsforwarder.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DestinationRepository(private val db: ForwarderDatabase) {
    private val dao = db.destinations()
    val destinations: Flow<List<Destination>> = dao.observe().map { list -> list.map { it.toDestination() } }
    suspend fun all(): List<Destination> = dao.all().map { it.toDestination() }
    suspend fun save(destination: Destination): Long = dao.save(destination.toEntity())

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
