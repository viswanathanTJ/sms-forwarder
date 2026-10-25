package com.viswa2k.smsforwarder.data.repo

import androidx.room.withTransaction
import com.viswa2k.smsforwarder.core.Destination
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
}
