package com.viswa2k.smsforwarder.core

/** One PDU of an incoming SMS: [sentAt] is `SmsMessage.timestampMillis`, [subscriptionId] null when unknown. */
data class Pdu(val sender: String, val body: String, val sentAt: Long, val subscriptionId: Int?)

/**
 * A long SMS arrives as several PDUs in one broadcast. Consecutive parts from the same sender are joined
 * in order into one [IncomingSms], so the message is classified and forwarded once, on its full text.
 */
object IncomingSmsAssembler {
    fun assemble(pdus: List<Pdu>, receivedAt: Long): List<IncomingSms> {
        val out = ArrayList<IncomingSms>()
        var current: Pdu? = null
        val body = StringBuilder()
        fun flush() {
            val c = current ?: return
            out += IncomingSms(c.sender, body.toString(), c.sentAt, receivedAt, c.subscriptionId)
            body.setLength(0)
            current = null
        }
        for (p in pdus) {
            if (current?.sender != p.sender) flush()
            if (current == null) current = p
            body.append(p.body)
        }
        flush()
        return out
    }
}
