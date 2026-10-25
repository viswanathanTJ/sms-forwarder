package com.viswa2k.smsforwarder.data.db

import com.viswa2k.smsforwarder.core.MessageType

/** Comma-separated encodings used by [RuleEntity] (types as stable codes, destinations as ids). */
object Converters {
    fun typesToCsv(types: Set<MessageType>): String = types.map { it.code }.sorted().joinToString(",")

    fun csvToTypes(csv: String): Set<MessageType> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull()?.let(MessageType::fromCode) }.toSet()

    fun idsToCsv(ids: List<Long>): String = ids.joinToString(",")

    fun csvToIds(csv: String): List<Long> = csv.split(',').mapNotNull { it.trim().toLongOrNull() }
}
