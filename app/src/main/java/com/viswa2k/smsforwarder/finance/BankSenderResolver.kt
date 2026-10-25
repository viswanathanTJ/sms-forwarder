// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/BankSenderResolver.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import com.viswa2k.smsforwarder.classify.KeywordLists
import com.viswa2k.smsforwarder.classify.SenderIds
import java.util.concurrent.ConcurrentHashMap

/** A sender address resolved to a bank / brand (spec 05 §2). */
data class BankSender(
    /** Address as received. */
    val raw: String,
    /** Canonical 6-character id (`HDFCBK`), or the number for phone senders. */
    val id: String,
    /** Display name ("HDFC Bank", "Paytm"), or [id] when unknown. */
    val name: String,
    /** Logo drawable name of the original (`logo_hdfc`), "" when none. */
    val logo: String,
    /** Bank (isBank = 1): bank table, forced brand names, or the bank-header heuristic. */
    val isBank: Boolean,
    /** Which lookup decided ([Source.UNKNOWN] = name is the id). */
    val source: Source,
    /** `ML.g` routing: header or short code (full mobile numbers are never parsed). */
    val isBusinessRoute: Boolean,
    /** All-digit sender: the parser rejects it. */
    val isDigitsOnly: Boolean,
) {
    enum class Source { PHONE, BANK_TABLE, BRAND_TABLE, HEURISTIC, UNKNOWN }

    /** Name without "Bank", for grouping ("HDFC Bank" and "HDFC" are the same bank). */
    val bankKey: String get() = normalizeBankName(name)

    companion object {
        /** "SBI YONO" / "SBI UPI" are bank "SBI" (spec 05 §3.15 step 4); case/space-insensitive key. */
        fun normalizeBankName(name: String): String {
            val n = name.trim()
            val mapped = when {
                n.equals("SBI YONO", true) || n.equals("SBI UPI", true) -> "SBI"
                else -> n
            }
            return mapped.lowercase().replace(Rx.SPACES, " ")
        }
    }
}

/**
 * Sender identification (spec 05 §2): canonical id → bank table (`getStringsender` / `getStringname` /
 * `getStringlogo`, 194 ids) → brand table (`getStringsenderFORID` / `getStringnameFORID`, 1,163 ids) →
 * bank-header heuristic → id. Pure and thread-safe; results are cached per address.
 *
 * **Coverage limit:** the original's third step, the 723k-row `sender database` sender database, is
 * proprietary and not shipped. It is replaced by [heuristicBank]: known bank-code stems (derived from the
 * 194 bank ids plus a few hand-listed issuers) and the "…BNK / …BK / …BANK" header shape. Unknown
 * non-bank headers keep their id as name and are not banks; the parser still parses them, so a wallet
 * or a small co-operative bank with an unlisted id produces transactions under its raw id.
 */
class BankSenderResolver(lists: KeywordLists) {

    private val bankById: Map<String, Pair<String, String>>
    private val brandById: Map<String, String>
    private val logoByName: Map<String, String>
    private val stems: Map<String, Pair<String, String>>

    init {
        val ids = lists["getStringsender"]
        val names = lists["getStringname"]
        val logos = lists["getStringlogo"]
        val bank = LinkedHashMap<String, Pair<String, String>>()
        ids.forEachIndexed { i, id ->
            val key = id.trim().uppercase()
            if (key.isNotEmpty() && key !in bank) bank[key] = names.getOrElse(i) { key } to logos.getOrElse(i) { "" }
        }
        bankById = bank.ifEmpty { BUILT_IN_BANKS }

        val bIds = lists["getStringsenderFORID"]
        val bNames = lists["getStringnameFORID"]
        val brand = HashMap<String, String>()
        bIds.forEachIndexed { i, id ->
            val key = id.trim().uppercase()
            val name = bNames.getOrElse(i) { "" }
            if (key.isNotEmpty() && name.isNotEmpty() && key !in brand) brand[key] = name
        }
        brandById = brand

        val lNames = lists["getStringSenderNames"] + lists["getStringnameLOG"]
        val lLogos = lists["getStringSenderLogos"] + lists["getStringnameLOGNAME"]
        val logo = HashMap<String, String>()
        lNames.forEachIndexed { i, n ->
            val l = lLogos.getOrElse(i) { "" }
            if (n.isNotBlank() && l.isNotBlank()) logo.putIfAbsent(n.lowercase(), l)
        }
        bank.values.forEach { (n, l) -> if (l.isNotBlank()) logo.putIfAbsent(n.lowercase(), l) }
        logoByName = logo

        // 4-letter stems of the bank ids that point to exactly one bank (HDFC, ICIC, AXIS, KOTA…).
        val stemNames = HashMap<String, MutableSet<String>>()
        bankById.forEach { (id, v) ->
            if (id.length >= 5 && id.take(4).all { it.isLetter() }) stemNames.getOrPut(id.take(4)) { HashSet() } += v.first
        }
        val s = HashMap<String, Pair<String, String>>()
        stemNames.forEach { (stem, set) ->
            if (set.size == 1 && stem !in AMBIGUOUS_STEMS) {
                val name = set.first()
                s[stem] = name to (bankById.values.firstOrNull { it.first == name }?.second ?: "")
            }
        }
        EXTRA_STEMS.forEach { (stem, name) ->
            s.putIfAbsent(stem, name to (bankById.values.firstOrNull { it.first == name }?.second ?: ""))
        }
        stems = s
    }

    private val cache = ConcurrentHashMap<String, BankSender>()

    /** Resolves [address] (spec 05 §2 steps 1–2). */
    fun resolve(address: String): BankSender = cache.getOrPut(address) { compute(address) }

    private fun compute(address: String): BankSender {
        val raw = address.trim()
        val business = SenderIds.isBusiness(raw)
        if (raw.startsWith("+")) {
            return BankSender(raw, raw, raw, "", false, BankSender.Source.PHONE, false, false)
        }
        val digitsOnly = raw.isNotEmpty() && raw.none { it.isLetter() }
        if (digitsOnly) {
            return BankSender(raw, raw.filter { it.isDigit() }, raw, "", false, BankSender.Source.PHONE, business, true)
        }
        val id = SenderIds.canonicalHeader(raw)
        bankById[id]?.let { (name, logo) ->
            return BankSender(raw, id, name, logo.ifBlank { logoFor(name) }, true, BankSender.Source.BANK_TABLE, business, false)
        }
        brandById[id]?.let { name ->
            val isBank = FORCED_BANK_NAMES.any { it.equals(name, ignoreCase = true) }
            return BankSender(raw, id, name, logoFor(name), isBank, BankSender.Source.BRAND_TABLE, business, false)
        }
        heuristicBank(id)?.let { (name, logo) ->
            return BankSender(raw, id, name, logo.ifBlank { logoFor(name) }, true, BankSender.Source.HEURISTIC, business, false)
        }
        return BankSender(raw, id, id, "", false, BankSender.Source.UNKNOWN, business, false)
    }

    /** Logo by display name (`allSenderID` → `allSenderLogo`), "" when none. */
    fun logoFor(name: String): String = logoByName[name.lowercase()].orEmpty()

    /**
     * Replacement for the sender DB (coverage limit, see class doc): a known bank stem
     * (`HDFCxx` → HDFC Bank), or a header shaped like a bank (`…BNK`, `…BK`, contains `BANK`).
     */
    private fun heuristicBank(id: String): Pair<String, String>? {
        if (id.length < 4 || id.all { it.isDigit() }) return null
        stems[id.take(4)]?.let { return it }
        if (id.startsWith("SBI")) return "SBI" to "logo_sbi"
        if (BANK_SHAPE.containsMatchIn(id)) return id to ""
        return null
    }

    companion object {
        /** Brand-table names that are banks (spec 05 §2 step 2.2). */
        val FORCED_BANK_NAMES = listOf("SBI", "SBI YONO", "SBI UPI", "AMEX", "Bank of Baroda", "BOB Financial", "SlicePay")

        private val BANK_SHAPE = Regex("(BNK|BANK|BK)$|BANK")

        /** Stems shared by unrelated ids (ATMSBI/ATMSBM, …) are not used. */
        private val AMBIGUOUS_STEMS = setOf("ATMS", "CBSS", "FROM")

        /** Issuers whose headers vary more than the table shows. */
        private val EXTRA_STEMS = mapOf(
            "ICIC" to "ICICI Bank",
            "HDFC" to "HDFC Bank",
            "AXIS" to "Axis Bank",
            "KOTK" to "Kotak Mahindra Bank",
            "KOTA" to "Kotak Mahindra Bank",
            "IDFC" to "IDFC First Bank",
            "INDU" to "IndusInd Bank",
            "CANB" to "Canara Bank",
            "PNBS" to "PNB",
            "YESB" to "Yes Bank",
            "FEDB" to "Federal Bank",
            "RBLB" to "RBL Bank",
            "IDBI" to "IDBI Bank",
            "UNIO" to "Union Bank",
            "CITI" to "Citibank",
            "HSBC" to "HSBC Bank",
            "AMEX" to "AMEX",
            "BOBC" to "Bank of Baroda",
            "BOBT" to "Bank of Baroda",
        )

        /** Used only when the keyword asset is missing (tests / degraded start). */
        private val BUILT_IN_BANKS = mapOf(
            "HDFCBK" to ("HDFC Bank" to "logo_hdfc"),
            "ICICIB" to ("ICICI Bank" to "logo_icici"),
            "AXISBK" to ("Axis Bank" to "logo_axis_bank"),
            "SBIINB" to ("SBI" to "logo_sbi"),
            "KOTAKB" to ("Kotak Mahindra Bank" to "logo_kotak_mahindra_bank"),
        )
    }
}
