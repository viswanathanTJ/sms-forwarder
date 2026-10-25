package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.ClassifierInput
import com.viswa2k.smsforwarder.classify.FraudLevel
import com.viswa2k.smsforwarder.classify.KeywordLists
import com.viswa2k.smsforwarder.classify.MessageCategory
import com.viswa2k.smsforwarder.classify.MessageClassifier
import com.viswa2k.smsforwarder.classify.OtpResult
import com.viswa2k.smsforwarder.classify.SenderInfo
import com.viswa2k.smsforwarder.classify.SenderIds
import com.viswa2k.smsforwarder.finance.BankSender
import com.viswa2k.smsforwarder.finance.BankSenderResolver
import com.viswa2k.smsforwarder.finance.ParsedTransaction
import com.viswa2k.smsforwarder.finance.TransactionParser

/** `UNVERIFIED` = phone number while the contacts permission is missing. */
enum class SenderKind { BUSINESS, CONTACT, UNKNOWN_NUMBER, UNVERIFIED }

data class Analysis(
    val type: MessageType,
    val sender: SenderInfo,
    val senderName: String,
    val otp: OtpResult?,
    val txn: ParsedTransaction?,
    val missedCall: MissedCall?,
    val fraud: FraudLevel,
    val classifierAvailable: Boolean,
)

/**
 * Classifies one SMS into a forwarder [MessageType] (spec 5.1, 7). Missed-call detection runs
 * first; a payment OTP is OTP only. `lists == null` means the keyword asset failed to load: only
 * the missed-call detector and the business/phone split remain.
 */
class MessageAnalyzer(lists: KeywordLists?) {
    private val classifier = lists?.let { MessageClassifier(it) }
    private val parser = lists?.let { TransactionParser(it) }
    private val resolver = lists?.let { BankSenderResolver(it) }

    fun analyze(sender: String, body: String, senderKind: SenderKind): Analysis {
        val info = SenderIds.parse(sender)
        val name = resolver?.resolve(sender)?.takeIf { it.source != BankSender.Source.UNKNOWN && it.source != BankSender.Source.PHONE }?.name
            ?: titleCase(info)

        MissedCallDetector.detect(sender, body)?.let {
            return Analysis(MessageType.MISSED_CALL, info, name, null, null, it, FraudLevel.NONE, classifier != null)
        }
        if (classifier == null || parser == null) {
            val type = if (info.isBusiness) MessageType.IMPORTANT else MessageType.PERSONAL
            return Analysis(type, info, name, null, null, null, FraudLevel.NONE, false)
        }

        val c = classifier.classify(ClassifierInput(sender, body, isKnown = senderKind == SenderKind.CONTACT))
        var txn: ParsedTransaction? = null
        val cat = c.category
        val type = when {
            c.fraudLevel == FraudLevel.SCAM || c.inSpamStore -> MessageType.SPAM
            cat.isOtp -> MessageType.OTP
            cat == MessageCategory.TRANSACTION -> {
                val parsed = parser.parse(sender, body)
                if (parsed.amount > 0) {
                    txn = parsed
                    MessageType.TRANSACTION
                } else {
                    MessageType.IMPORTANT
                }
            }
            cat == MessageCategory.BALANCE_ALERT -> {
                txn = parser.parse(sender, body)
                MessageType.BALANCE
            }
            cat == MessageCategory.SMART_ALERT -> MessageType.BILL
            cat == MessageCategory.DELIVERY -> MessageType.DELIVERY
            !info.isBusiness -> MessageType.PERSONAL
            else -> MessageType.IMPORTANT
        }
        return Analysis(type, info, name, c.otp.takeIf { type == MessageType.OTP }, txn, null, c.fraudLevel, true)
    }

    /** Unknown business header "AMAZON" -> "Amazon"; phone numbers and short codes stay as they are. */
    private fun titleCase(info: SenderInfo): String =
        if (info.isBusiness && info.canonicalId.any { it.isLetter() }) {
            info.canonicalId.lowercase().replaceFirstChar { it.uppercase() }
        } else {
            info.canonicalId
        }
}
