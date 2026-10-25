package com.viswa2k.smsforwarder.core

import com.viswa2k.smsforwarder.classify.FraudLevel
import com.viswa2k.smsforwarder.finance.Money
import com.viswa2k.smsforwarder.finance.ParsedTransaction
import com.viswa2k.smsforwarder.finance.TxnDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One received SMS (already joined from its PDUs). Times are epoch milliseconds. */
data class IncomingSms(
    val sender: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val subscriptionId: Int?,
)

/**
 * Renders a forwarded message (spec 5.5). Telegram text is HTML: every piece of user / SMS text goes
 * through [escapeHtml], and truncation to [TELEGRAM_MAX] shortens only the original-text part so the
 * tags stay balanced. SMS text is plain.
 */
object MessageFormatter {
    const val TELEGRAM_MAX = 4096
    private const val ELLIPSIS = "…"
    private val TOKEN = Regex("%[smroat]")
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun format(
        sms: IncomingSms,
        a: Analysis,
        style: Style,
        template: String?,
        channel: Channel,
        deviceName: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val html = channel == Channel.TELEGRAM
        return when (style) {
            Style.ORIGINAL -> original(sms, deviceName, html)
            Style.CUSTOM -> custom(sms, a, template ?: "%m", deviceName, html)
            Style.SMART -> if (html) smartTelegram(sms, a, deviceName, zone) else smartSms(sms, a, deviceName, zone)
        }
    }

    // ---- Original -------------------------------------------------------------------------

    private fun original(sms: IncomingSms, device: String, html: Boolean): String =
        if (html) {
            withTail("<b>${escapeHtml(sms.sender)}</b> · ${escapeHtml(device)}\n", sms.body, "")
        } else {
            "From ${sms.sender}: ${sms.body}"
        }

    // ---- Custom ---------------------------------------------------------------------------

    private fun custom(sms: IncomingSms, a: Analysis, template: String, device: String, html: Boolean): String {
        fun esc(s: String) = if (html) escapeHtml(s) else s
        val tpl = esc(template.replace("\\n", "\n"))
        fun render(message: String): String = TOKEN.replace(tpl) { m ->
            when (m.value) {
                "%s" -> esc(sms.sender)
                "%m" -> message
                "%r" -> esc(device)
                "%o" -> esc(a.otp?.code.orEmpty())
                "%a" -> esc(amountOf(a.txn, plain = false))
                "%t" -> esc(a.type.label)
                else -> ""
            }
        }
        val full = render(esc(sms.body))
        if (!html || full.length <= TELEGRAM_MAX) return full
        // Shorten only the message value; the (escaped, tag-free) template stays whole.
        val overflow = full.length - TELEGRAM_MAX
        val escBody = esc(sms.body)
        val keep = (escBody.length - overflow - ELLIPSIS.length).coerceAtLeast(0)
        val out = render(cutEscaped(escBody, keep) + ELLIPSIS)
        return if (out.length <= TELEGRAM_MAX) out else cutEscaped(out, TELEGRAM_MAX - ELLIPSIS.length) + ELLIPSIS
    }

    // ---- Smart (Telegram) ---------------------------------------------------------------------

    private fun smartTelegram(sms: IncomingSms, a: Analysis, device: String, zone: ZoneId): String {
        val sender = escapeHtml(sms.sender)
        val dev = escapeHtml(device)
        val warn = fraudLabel(a.fraud)?.let { "⚠ $it\n" }.orEmpty()
        val txn = a.txn
        return when {
            a.type == MessageType.MISSED_CALL && a.missedCall != null -> {
                val mc = a.missedCall
                val head = if (mc.count > 1) "${mc.count} missed calls" else "Missed call"
                "📞 $head\n$warn${escapeHtml(callerLine(mc.caller, mc.time, sms, zone))}\n$dev · via $sender"
            }
            a.type == MessageType.OTP && a.otp != null -> {
                val name = escapeHtml(a.senderName)
                withTail("🔐 OTP <code>${escapeHtml(a.otp.code)}</code>\n$warn$name · $sender · $dev\n<i>", sms.body, "</i>")
            }
            a.type == MessageType.TRANSACTION && txn != null && txn.amount > 0 && txn.direction != TxnDirection.NONE -> {
                val lines = mutableListOf("💸 ${directionWord(txn)} ${rupees(txn.amountMinor)}")
                if (warn.isNotEmpty()) lines += warn.trimEnd()
                counterpartyLine(txn)?.let { lines += escapeHtml(it) }
                accountLine(txn, a.senderName)?.let { lines += escapeHtml(it) }
                txn.balanceMinor?.let { lines += "Balance ${rupees(it)}" }
                lines += "$dev · $sender"
                lines.joinToString("\n")
            }
            else -> withTail("${a.type.emoji} ${a.type.label}\n$warn$sender · $dev\n<i>", sms.body, "</i>")
        }
    }

    // ---- Smart (SMS) --------------------------------------------------------------------------

    private fun smartSms(sms: IncomingSms, a: Analysis, device: String, zone: ZoneId): String {
        val warn = fraudLabel(a.fraud)?.let { "$it: " }.orEmpty()
        val txn = a.txn
        return when {
            a.type == MessageType.MISSED_CALL && a.missedCall != null -> {
                val mc = a.missedCall
                val head = if (mc.count > 1) "${mc.count} missed calls" else "Missed call"
                "$warn$head: ${callerLine(mc.caller, mc.time, sms, zone)} ($device)"
            }
            a.type == MessageType.OTP && a.otp != null -> "${warn}OTP ${a.otp.code} - ${a.senderName} ($device)"
            a.type == MessageType.TRANSACTION && txn != null && txn.amount > 0 && txn.direction != TxnDirection.NONE -> {
                val parts = mutableListOf("${directionWord(txn)} ${amountOf(txn, plain = true)}")
                counterpartyLine(txn)?.let { parts += it }
                accountLine(txn, a.senderName)?.let { parts += it }
                txn.balanceMinor?.let { parts += "Bal ${Money.format(it).replace("₹ ", "Rs ")}" }
                "$warn${parts.joinToString(", ")} ($device)"
            }
            else -> "$warn${a.type.label} from ${sms.sender} ($device): ${sms.body}"
        }
    }

    // ---- Pieces ---------------------------------------------------------------------------

    private fun fraudLabel(f: FraudLevel): String? = when (f) {
        FraudLevel.NONE -> null
        FraudLevel.SUSPICIOUS -> "Suspicious"
        FraudLevel.SCAM -> "Scam"
        FraudLevel.FINANCIAL_RISK -> "Financial risk"
        FraudLevel.MISLEADING -> "Misleading"
        FraudLevel.FAKE -> "Fake"
    }

    private fun rupees(paise: Long): String = Money.format(paise).replace("₹ ", "₹")

    private fun amountOf(txn: ParsedTransaction?, plain: Boolean): String {
        if (txn == null || txn.amount <= 0) return ""
        return if (plain) Money.format(txn.amountMinor).replace("₹ ", "Rs ") else rupees(txn.amountMinor)
    }

    private fun directionWord(txn: ParsedTransaction) =
        if (txn.direction == TxnDirection.CREDIT) "Credited" else "Debited"

    /** "To Swiggy · UPI" / "From Ravi · NEFT"; null when neither merchant nor a real mode was parsed. */
    private fun counterpartyLine(txn: ParsedTransaction): String? {
        val mode = txn.paymentMode.takeIf {
            it.isNotBlank() && !it.equals("Debited", true) && !it.equals("Credited", true)
        }
        val who = txn.merchant.takeIf { it.isNotBlank() }?.let { (if (txn.direction == TxnDirection.CREDIT) "From " else "To ") + it }
        return listOfNotNull(who, mode).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun accountLine(txn: ParsedTransaction, bank: String): String? {
        val acct = txn.cardLast4?.takeIf { it.isNotEmpty() }?.let { "Card XX$it" }
            ?: txn.accountLast4?.takeIf { it.isNotEmpty() && it != "-1" }?.let { "A/c XX$it" }
        return listOfNotNull(acct, bank.takeIf { it.isNotBlank() }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun callerLine(caller: String?, time: String?, sms: IncomingSms, zone: ZoneId): String {
        val at = time ?: Instant.ofEpochMilli(sms.receivedAt).atZone(zone).format(HHMM)
        return "${caller ?: "Unknown number"} at $at"
    }

    // ---- Truncation -----------------------------------------------------------------------

    /**
     * `head + escape(original) + tail`, with the escaped original shortened (and "…" appended) so the
     * total stays within [TELEGRAM_MAX]. [head] and [tail] are already escaped / tag-balanced.
     */
    private fun withTail(head: String, original: String, tail: String): String {
        val esc = escapeHtml(original)
        val budget = TELEGRAM_MAX - head.length - tail.length
        if (esc.length <= budget) return head + esc + tail
        return head + cutEscaped(esc, (budget - ELLIPSIS.length).coerceAtLeast(0)) + ELLIPSIS + tail
    }

    /** First [max] chars of already-escaped [s], never ending inside an `&amp;`-style entity or a surrogate pair. */
    private fun cutEscaped(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        val amp = s.lastIndexOf('&', end - 1)
        if (amp >= 0 && amp >= end - 5 && s.indexOf(';', amp).let { it < 0 || it >= end }) end = amp
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }
}
