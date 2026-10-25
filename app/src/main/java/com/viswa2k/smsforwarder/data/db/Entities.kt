package com.viswa2k.smsforwarder.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.viswa2k.smsforwarder.core.Channel
import com.viswa2k.smsforwarder.core.Destination
import com.viswa2k.smsforwarder.core.Rule
import com.viswa2k.smsforwarder.core.SenderCondition
import com.viswa2k.smsforwarder.core.Style

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val allMessages: Boolean,
    /** [MessageType] codes, comma separated. */
    val types: String,
    val sender: Int,
    /** Destination ids, comma separated, in order. */
    val destinationIds: String,
    val style: Int,
    val customTemplate: String?,
    val position: Int,
)

@Entity(tableName = "destinations")
data class DestinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: Int,
    val label: String,
    val address: String,
    val topicId: Long?,
    val simSubscriptionId: Int?,
)

/**
 * One received SMS. [key] identifies the message (spec review focus 2): the unique index makes a
 * second insert of the same SMS a no-op. [skip] is `SkipReason.code`, [senderKind] is `SenderKind.code`.
 * [otpCode] exists only until the row is masked, then is nulled.
 */
@Entity(tableName = "logs", indices = [Index(value = ["key"], unique = true), Index("receivedAt")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val type: Int,
    val senderKind: Int,
    /** One display line, e.g. "Code 482913" or "₹1,250 debit". */
    val extracted: String,
    val matchedRules: String,
    val skip: Int?,
    val simSlot: Int?,
    val masked: Boolean = false,
    val otpCode: String? = null,
)

/** [state]: 0 PENDING, 1 SENT, 2 FAILED. */
@Entity(
    tableName = "outbox",
    foreignKeys = [ForeignKey(LogEntity::class, parentColumns = ["id"], childColumns = ["logId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("logId"), Index("state", "nextAttemptAt")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val destinationId: Long,
    val channel: Int,
    val text: String,
    val state: Int = STATE_PENDING,
    val attempts: Int = 0,
    val firstAttemptAt: Long,
    val nextAttemptAt: Long,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
    }
}

data class LogWithSends(
    @Embedded val log: LogEntity,
    @Relation(parentColumn = "id", entityColumn = "logId") val sends: List<OutboxEntity>,
)

data class Counts(val forwarded: Int, val skipped: Int, val retrying: Int)

fun Rule.toEntity() = RuleEntity(
    id = id, name = name, enabled = enabled, allMessages = allMessages,
    types = Converters.typesToCsv(types), sender = sender.code,
    destinationIds = Converters.idsToCsv(destinationIds), style = style.code,
    customTemplate = customTemplate, position = position,
)

fun RuleEntity.toRule() = Rule(
    id = id, name = name, enabled = enabled, allMessages = allMessages,
    types = Converters.csvToTypes(types),
    sender = SenderCondition.entries.firstOrNull { it.code == sender } ?: SenderCondition.ANYONE,
    destinationIds = Converters.csvToIds(destinationIds),
    style = Style.entries.firstOrNull { it.code == style } ?: Style.SMART,
    customTemplate = customTemplate, position = position,
)

fun Destination.toEntity() = DestinationEntity(id, channel.code, label, address, topicId, simSubscriptionId)

fun DestinationEntity.toDestination() = Destination(
    id = id,
    channel = Channel.entries.firstOrNull { it.code == channel } ?: Channel.TELEGRAM,
    label = label, address = address, topicId = topicId, simSubscriptionId = simSubscriptionId,
)
