// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/FinanceText.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

import java.text.Normalizer

/**
 * Shared regex fragments and the text preparation of spec 05 §3.1 (steps 1–3, 7, 8: NFKC, URL
 * stripping, ordinal clean-up, newline marker, title dots, the `V.b()` normaliser).
 *
 * **`[[:punct:]]`** (spec 05 §3.4 / §9 q2): in Java this bracket is the literal set `: p u n c t`. The
 * rebuild deliberately uses `\p{Punct}` (ASCII punctuation), as §3.4 recommends; the corpus tests in
 * `TransactionParserTest` were written against that choice.
 */
internal object FinanceText {

    /** `[[:punct:]]` of the original, as `\p{Punct}` (see the object doc). */
    const val P = "\\p{Punct}"

    /**
     * `ACC` (spec 05 §3.4): an account keyword not preceded by "beneficiary" / "benf" within 3 chars.
     * Kept as one non-capturing unit so callers can number their own groups.
     */
    const val ACC =
        "(?:(?<!(?:beneficiary|benf)[\\w\\W]{1,3})(?:a\\/c|account|acc|acct|accnt|[ .|-](?<!(?:beneficiary|benf)[\\w\\W]{1,3})ac|^ac))"

    /** Lazy number terminator used by the "…[0-9].*?(…)" patterns (spec 05 §3.8 step 2). */
    const val TERM = "(?:[a-z]|$|:|=|-|%|_|\\(|\\)|\\{|<| )"

    /** Terminator of the amount patterns of Stage H (no `%` / `)`). */
    const val AMT_TERM = "(?:[a-z]|$|:|=|-|_|\\(|\\{|<| )"

    /** Newline marker (spec 05 §3.1 step 7). */
    const val NL = " _______ "

    private val NUMBER_JUNK = Regex("(?i)((rs|inr)\\.|[a-zA-z]|(?!\\.)[\\W])")

    /**
     * The number clean-up rule (spec 05 §3.11): delete currency words, letters and every non-dot
     * non-word char, strip one trailing ".", parse; 0 on failure. A value with several dots keeps the
     * last one as the decimal point ("1.234.50" → 1234.50).
     */
    fun cleanNumber(token: String): Double {
        var s = NUMBER_JUNK.replace(token, "")
        s = s.replace(Rx.NON_NUMBER, "")
        if (s.endsWith(".")) s = s.dropLast(1)
        s = s.trimStart('.')
        if (s.count { it == '.' } > 1) {
            val last = s.lastIndexOf('.')
            s = s.substring(0, last).replace(".", "") + s.substring(last)
        }
        return s.toDoubleOrNull() ?: 0.0
    }

    /** Last [n] digits of the trailing digit run of [token] (`[0-9]{2,}`), or null. */
    fun lastDigits(token: String, n: Int = 4): String? =
        Rx.DIGITS2.findAll(token).lastOrNull()?.value?.takeLast(n)

    // ------------------------------------------------------------------ §3.1 steps 1–3

    private val URL_TOKEN = Regex(
        "(?i)(https?://\\S+|www\\.\\S+|\\b[a-zA-Z0-9-]+\\.(com|in|co|org|net|io|ly|me|gov|edu|info|app|link|ai)/[a-zA-Z0-9-/?=&._%]*)",
    )

    /** Steps 1–2: NFKC and URL stripping (`bodyLowerNoUrls` is this, lower-cased). */
    fun stripUrls(text: String): String = URL_TOKEN.replace(text, " ")

    private val ORDINAL = Regex(
        "(?i)([0-9]{1,2})(st|nd|rd|th)(\\W+|)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)",
    )

    // ------------------------------------------------------------------ §3.1 step 7

    private val TITLE_DOT = Regex("(?i)(\\W|^)(mr|mrs|ms|dr|m\\/s)\\.(\\W|\\w)")

    // ------------------------------------------------------------------ §3.1 step 8 (normaliser)

    private data class Rule(val regex: Regex, val replacement: String)

    private fun r(pattern: String, replacement: String) = Rule(Regex(pattern), replacement)

    /**
     * The currency rewrite the rebuild must do first (spec 05 §3.11): `₹`, `Rs.`, `Rs:`, `INR.` → "Rs ".
     */
    private val CURRENCY = listOf(
        r("₹\\s*", " Rs "),
        r("(?i)(\\W|^)(rs|inr)\\s*[.:]+\\s*(?=[0-9.])", "$1Rs "),
        r("(?i)(\\W|^)rs\\.", "$1 Rs "),
        r("(?i)(\\W|^)inr\\.", "$1INR "),
        r("(?i)(rs|inr)(\\W)(\\.)(\\d)", "$1 $4"),
        r("(?i)[(](rs)[)]", " rs "),
        r("(?i)\\b(rs|inr)(?=[0-9])", "$1 "),
        r("(?i)\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:/-)", "$1 "),
    )

    private val ACCOUNT_WORDS = listOf(
        r("(?i)\\baccno\\b", "acc no"),
        r("(?i)\\baccountno\\b", "account no"),
    )

    /**
     * Masked digits are canonicalised to "XX…" (spec 05 §3.1 step 8): `**1234`, `xx-1234`, `x1234`,
     * `XXXX XXXX 1234`, `1234XXXXXX5678` (keeps the trailing digits), `(**-1234)`.
     */
    private val MASKS = listOf(
        r("\\(([xX*]{2,})-([0-9]+)\\)", "XX$2"),
        r("(?<![0-9A-Za-z])(?:[0-9]{1,6}[\\s/-]?)?[xX*•#]{2,}(?:[\\s/-]?[xX*•#]{2,})*[\\s/-]?([0-9]{2,})", "XX$1"),
        r("(?<=[\\s:./-]|^)\\.{2,}\\s?([0-9]{2,})", "XX$1"),
        r("(?<![A-Za-z0-9])[xX*]([0-9]{3,})(?![0-9])", "XX$1"),
        r("(?i)(xx)-([0-9]+)", "$1$2"),
    )

    private val AVL_BAL = r("(?i)(avl|available|avlbl|avail|avlb)\\.?\\s*(bal|balance|bl)\\b\\.?", "avl bal")

    /** Promotional amounts are blanked so they never become the transaction amount. */
    private val PROMO_BLANKS = listOf(
        "(?i)(\\W|^)(rs|inr)(\\W+|)[0-9,.]+\\s*off",
        "(?i)(\\W|^)up(\\W+|)to\\W(rs|inr)(\\W+|)[0-9,.]+",
        "(?i)(\\W|^)voucher\\W+worth\\W(rs|inr)(\\W+|)[0-9,.]+",
        "(?i)(\\W|^)(enjoy|get)\\W+(a\\W|)(rs|inr)(\\W+|)[0-9,.]+",
        "(?i)\\W(maintain)\\W+(rs|inr)(\\W+|)[0-9,.]+",
        "(?i)(\\W|^)(rs|inr)(\\W+|)[0-9,.]+(\\W|\\w){5,15}voucher",
        "(?i)emi\\sof\\s(rs|inr)(\\W+|)[0-9,\\.]+",
        "(?i)(avg|average|avrg|MAB|QAB|AQB|AMB|monthly)\\W+bal(\\w+|)\\W+(\\W|\\w){1,35}(rs|inr)\\W+[0-9.,]+\\W",
        "(?i)split\\s(your\\s|)(.*?)(rs|inr)(\\W+|)[0-9,.]+",
        "(?i)credit\\s(bal|balance)(\\W+|)of\\s(rs|inr)\\W+(\\d+\\.\\d+|\\d+)",
        "(?i)(loan|wallet|increase(|d))\\W+(limit|lmt)",
        "(?i)eligibl(\\w|\\W){1,4}\\s*(for|of)\\W+(a\\W+|)(\\w+\\W+){0,3}(rs|inr)(\\W+|)[0-9,.]+",
    ).map { Rule(Regex(it), " ") }

    /** Conditional negation ("if you have not done this…") is removed (spec 05 §3.1 / §3.13 rule 7). */
    private val CONDITIONAL_NEGATION = r(
        "(?i)if(\\syou\\shave|)\\s(not|dis|isn('|)t|wasn('|)t|hasn('|)t|couldn('|)t|could not be)\\s*(-|)\\s*(been|)\\s*(approved|authorized|successful)",
        " ",
    )

    /** Phrase canonicalisation into "Rs X debited / credited …" (spec 05 §3.1 step 8). */
    private val PHRASES = listOf(
        r("(?i)^(money\\s*|)received\\s+((rs|inr)\\W*[0-9][0-9,.]*)\\s+in\\b", "$2 credited in"),
        r("(?i)^sent\\s*((rs|inr)\\W*[0-9][0-9,.]*)(.*?)\\bfrom\\b", "$1 debited$3 from"),
        r("(?i)^(imps\\s*(rs|inr)\\W*[0-9][0-9,.]*)[\\W_]+(sent\\sfrom)", "$1 debited from"),
        r("(?i)^((rs|inr)\\W*[0-9][0-9,.]*\\s)sent(\\sfrom\\s.*?(XX[0-9]+))", "$1debited$3"),
        r("(?i)^((rs|inr)\\W*[0-9][0-9,.]*\\s)received(\\sin\\s.*?(XX[0-9]+))", "$1credited$3"),
        r("(?i)^(thank\\syou\\s.{0,40}?)((rs|inr)\\W*[0-9][0-9,.]*)\\s*(has\\sbeen|is)\\sreceived", "$1$2 credited, payment received"),
        r("(?i)(\\W|^)(used)\\s*(for\\s+|)((rs|inr)(\\W+|)\\d)", "$1spent $4"),
        r("(?i)(thank\\s*you\\s*for\\s*using\\b.{0,80}?)\\bfor\\s+((rs|inr)\\s*[0-9][0-9,.]*)", "$1 spent $2"),
        r("(?i)(amt|amount)\\s*sent\\s*((rs|inr)\\W*[0-9][0-9,.]*)", "$2 debited"),
        r("(?i)(\\W)(cr)\\s(with\\W(rs|inr))", "$1credited $3"),
        r("(?i)([x0-9]+)\\s(dr\\.)(\\s(rs|inr))", "$1 debited$3"),
        r("(?i)(credited)\\s(to beneficiary)", "paid $2"),
        r("(?i)has\\sa\\scredit\\sby", "is credited by"),
        r("(?i)has\\s*(a|)\\scredit\\sfor", "is credited for"),
        r("(?i)credit\\s*:\\s*(rs|inr)", "credited $1"),
        r("(?i)(avl|available|avlbl|avail)\\s*(amt|amount)", "avl bal"),
        r("(?i)^upi\\smandate\\s*:[\\W_]+", ""),
        r("(?i)(\\W)(info)\\s*(ach|Bil|self|atd|neft|eba|INF|CMS|mcd|rtgs|APBS|home|clg|trf)(\\W)", "$1 $3$4"),
    )

    /**
     * Stage E's pre-normalisation (spec 05 §3.8 step 0), applied to the whole text so Stage H sees the
     * amount too: "debited by 500." → "debited by inr 500.".
     */
    private val DEBIT_BY = r("(?i)(debit(|ed)\\s(by|of|with))(\\W+)(\\d+\\.\\d+|\\d+)(\\W)", "$1$4inr $5$6")

    /** Account / card tokens (spec 05 §3.1 step 8). */
    private val TOKENS = listOf(
        r("(?i)(credit(\\W+|)card)\\s(a\\/c|account|acc|acct|accnt|ac)\\b", "$1"),
        r("(?i)\\b(bank|SFB)\\sCC\\b", "$1 credit card"),
        r("(?i)\\b(bank|SFB)\\sDC\\b", "$1 debit card"),
        r("(?i)(Super)\\s*(card)\\s([0-9]+)", "credit card XX$3"),
        r("SBA(\\d+)", "a/c XX$1"),
        r("[0-9]{2,}/SOD/([0-9]{2,})", "a/c XX$1"),
        r("(?i)Deposit\\W+(no|num|number)\\W+([0-9]+|)([x*.]+|)([0-9]+)", "Deposit a/c XX$4"),
        r("(?i)(credit(\\W+|)card)\\W+payment\\W+((x|\\*|)+(\\W+|)[0-9]{2,})", "$1 $3 payment"),
        r("(?i)^payment\\W+successful\\W*", ""),
        r("(?i)Meal(\\W+|)card\\Wcard", "Meal card"),
        r("(?i)Prepaid(\\W+|)card\\Wcard", "Prepaid card"),
    )

    /**
     * The full text preparation (§3.1 steps 1–3 and 7–8). Returns the normalised text, original case,
     * newlines replaced by [NL].
     */
    fun prepare(body: String): String {
        var t = Normalizer.normalize(body, Normalizer.Form.NFKC)
        t = stripUrls(t)
        t = ORDINAL.replace(t, "$1$3$4")
        t = t.replace("\r\n", "\n").replace("\n", NL)
        t = TITLE_DOT.replace(t, "$1$2$3")
        for (rule in CURRENCY) t = rule.regex.replace(t, rule.replacement)
        for (rule in ACCOUNT_WORDS) t = rule.regex.replace(t, rule.replacement)
        for (rule in MASKS) t = rule.regex.replace(t, rule.replacement)
        t = AVL_BAL.regex.replace(t, AVL_BAL.replacement)
        for (rule in PROMO_BLANKS) t = rule.regex.replace(t, rule.replacement)
        t = CONDITIONAL_NEGATION.regex.replace(t, CONDITIONAL_NEGATION.replacement)
        t = t.trim()
        for (rule in PHRASES) t = rule.regex.replace(t, rule.replacement)
        t = DEBIT_BY.regex.replace(t, DEBIT_BY.replacement)
        for (rule in TOKENS) t = rule.regex.replace(t, rule.replacement)
        t = t.replace(Rx.MULTI_SPACE, " ")
        return " " + t.trim() + " "
    }

    /** Index of the whitespace-delimited word around [start, end). */
    fun wordAround(text: String, start: Int, end: Int): IntRange {
        var s = start
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        var e = end
        while (e < text.length && !text[e].isWhitespace()) e++
        return s until e
    }

    /** Text of [text] in the [n] chars before [index]. */
    fun before(text: String, index: Int, n: Int): String = text.substring((index - n).coerceAtLeast(0), index.coerceAtMost(text.length))

    /** Text of [text] in the [n] chars after [index]. */
    fun after(text: String, index: Int, n: Int): String {
        val s = index.coerceIn(0, text.length)
        return text.substring(s, (s + n).coerceAtMost(text.length))
    }

    /** Title case ("SWIGGY LTD" → "Swiggy Ltd") for merchant display (spec 05 §3.10 step 9). */
    fun titleCase(s: String): String = s.trim().split(Rx.SPACES).filter { it.isNotEmpty() }.joinToString(" ") { w ->
        if (w.length <= 1) w.uppercase() else w.substring(0, 1).uppercase() + w.substring(1).lowercase()
    }
}
