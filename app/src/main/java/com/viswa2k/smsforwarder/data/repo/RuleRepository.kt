package com.viswa2k.smsforwarder.data.repo

import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.data.db.RuleDao
import com.viswa2k.smsforwarder.data.db.toEntity
import com.viswa2k.smsforwarder.data.db.toRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RuleRepository(private val dao: RuleDao) {
    val rules: Flow<List<Rule>> = dao.observe().map { list -> list.map { it.toRule() } }
    suspend fun all(): List<Rule> = dao.all().map { it.toRule() }

    /** `id == 0` inserts at the end and returns the new id; otherwise replaces the rule. */
    suspend fun save(rule: Rule): Long = dao.save(rule.toEntity())
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun move(id: Long, newPosition: Int) = dao.move(id, newPosition)
    suspend fun setEnabled(id: Long, on: Boolean) = dao.setEnabled(id, on)
}
