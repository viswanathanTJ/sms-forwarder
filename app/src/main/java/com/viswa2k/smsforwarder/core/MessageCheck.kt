package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.finance.TxnDirection
import java.time.ZoneId
import java.util.Locale

/** What the Settings "Test a message" tool shows. Nothing here is stored or sent. */
data class CheckResult(
    val type: MessageType,
    val extracted: String,
    val senderKind: SenderKind,
    val matchedRules: List<String>,
    val destinations: List<String>,
    val skip: SkipReason?,
    val telegramPreview: String?,
    val smsPreview: String?,
)

/** The pipeline's decision steps, in memory (spec 5.9 "Test a message"); forwarding is assumed on. */
object MessageCheck {
    fun run(
        sender: String,
        body: String,
        senderKind: SenderKind,
        analyzer: MessageAnalyzer,
        rules: List<Rule>,
        destinations: List<Destination>,
        deviceName: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CheckResult {
        val analysis = analyzer.analyze(sender, body, senderKind)
        val decision = RuleEngine.decide(analysis.type, senderKind, sender, masterOn = true, rules, destinations)
        val names = rules.associate { it.id to it.name }
        val sms = IncomingSms(sender, body, now, now, null)
        fun preview(channel: Channel) = decision.sends.firstOrNull { it.destination.channel == channel }?.let {
            MessageFormatter.format(sms, analysis, it.style, it.template, channel, deviceName, zone)
        }
        return CheckResult(
            type = analysis.type,
            extracted = extractedLine(analysis),
            senderKind = senderKind,
            matchedRules = decision.matchedRuleIds.mapNotNull { names[it] },
            destinations = decision.sends.map { it.destination.label },
            skip = decision.skip,
            telegramPreview = preview(Channel.TELEGRAM),
            smsPreview = preview(Channel.SMS),
        )
    }

    /** One display line for the Log and the test tool, e.g. "Code 482913" or "₹1,250 debit". */
    fun extractedLine(a: Analysis): String = when {
        a.otp != null -> "Code ${a.otp.code}"
        a.type == MessageType.TRANSACTION && a.txn != null && a.txn.amount > 0 -> {
            val amount = String.format(Locale.US, "%,.2f", a.txn.amount).removeSuffix(".00")
            val dir = when (a.txn.direction) {
                TxnDirection.DEBIT -> " debit"
                TxnDirection.CREDIT -> " credit"
                else -> ""
            }
            "₹$amount$dir"
        }
        a.missedCall != null -> a.missedCall.caller?.let { "From $it" } ?: ""
        else -> ""
    }
}
