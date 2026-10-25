// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/LinkExtractor.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/** A web link found in a message body. */
data class Link(
    /** Text as found (trailing punctuation trimmed). */
    val text: String,
    /** Lower-case host without `www.` and port. */
    val host: String,
)

/**
 * Link extraction and shape checks (spec 04b §4.2 step 1, §5.5). Pure; patterns are compiled once.
 */
object LinkExtractor {

    /** TLDs accepted for scheme-less `domain.tld` links (05a getNewCases19[2] plus common ones). */
    private val TLDS = listOf(
        "com", "in", "co", "net", "org", "io", "ly", "me", "info", "biz", "app", "xyz", "top", "club", "online",
        "site", "live", "click", "link", "buzz", "icu", "shop", "store", "vip", "win", "bid", "loan", "work",
        "fun", "space", "tk", "ml", "ga", "cf", "gq", "cc", "pw", "ws", "gl", "gd", "to", "id", "at", "us", "uk",
        "ai", "gy", "su", "ru", "cn", "tv", "page", "website", "today", "money", "cash", "bet", "games", "bank",
    )

    /** TLDs that rarely carry legitimate Indian transactional links (shape check). */
    private val ODD_TLDS = setOf(
        "xyz", "top", "club", "online", "site", "live", "click", "link", "buzz", "icu", "vip", "win", "bid",
        "loan", "work", "fun", "space", "tk", "ml", "ga", "cf", "gq", "pw", "ws", "su", "ru", "cn", "website",
        "today", "money", "cash", "bet", "games", "cc",
    )

    /** Well-known URL shorteners. */
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "goo.gl", "cutt.ly", "rb.gy", "is.gd", "shorturl.at", "tiny.cc", "s.id",
        "t.ly", "rebrand.ly", "ow.ly", "v.gd", "shrtco.de", "bitly.ws", "u.to", "clck.ru",
    )

    /**
     * Hosts exempt from the suspicious-link rules (spec 04b §5.5 whitelist; 05a case21 pairs
     * `wa.me`, `api.whatsapp.com` and the government/registry domains of newcase13).
     */
    private val WHITELIST_SUFFIXES = listOf(
        "wa.me", "whatsapp.com", "gov.in", "nic.in", "gov", "incometaxindia.gov.in", "nsdl.co.in", "utiitsl.com",
        "airtel.in", "razorpay.com", "npci.org.in", "rbi.org.in", "uidai.gov.in", "sebi.gov.in", "google.com",
        "youtube.com", "youtu.be", "amazon.in", "amzn.in", "amzn.to", "flipkart.com", "fkrt.it", "paytm.com",
        "phonepe.com", "jio.com", "myjio.com", "irctc.co.in",
    )

    private val SCHEME = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+")
    private val BARE = Regex(
        "(?i)(?<![\\w@./-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:" + TLDS.joinToString("|") +
            "))(?![\\w-])(?::\\d{2,5})?(?:/[^\\s<>\"']*)?",
    )
    private val DIGIT_RUN = Regex("\\d{4,}")
    private val IP_HOST = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val TRAILING = Regex("[.,;:!?)\\]}'\"]+$")

    /** Links in [text], in order, de-duplicated. */
    fun extract(text: String): List<Link> {
        val found = LinkedHashMap<String, Link>()
        val taken = ArrayList<IntRange>()
        for (m in SCHEME.findAll(text)) {
            val t = m.value.replace(TRAILING, "")
            taken += m.range
            hostOf(t)?.let { found.putIfAbsent(t.lowercase(), Link(t, it)) }
        }
        for (m in BARE.findAll(text)) {
            if (taken.any { m.range.first in it }) continue
            val t = m.value.replace(TRAILING, "")
            val host = hostOf(t) ?: continue
            // A bare "x.in"-style token needs a real label: at least 2 letters before the TLD.
            if (host.substringBeforeLast('.').count { it.isLetter() } < 2) continue
            // "...thanks.In case" is a missing space, not a link: a Capitalised TLD ends a sentence.
            val tld = m.groupValues[1].substringAfterLast('.')
            if (tld.length > 1 && tld[0].isUpperCase() && tld.drop(1).all { it.isLowerCase() }) continue
            found.putIfAbsent(t.lowercase(), Link(t, host))
        }
        return found.values.toList()
    }

    /** [text] with every link replaced by a space (keyword rules must not see link text). */
    fun blank(text: String, links: List<Link>): String {
        var out = text
        for (l in links.sortedByDescending { it.text.length }) out = out.replace(l.text, " ", ignoreCase = true)
        return out
    }

    /** Lower-case host of [link] (no scheme, `www.`, port, path, credentials). */
    fun hostOf(link: String): String? {
        var s = link.lowercase()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        s = s.removePrefix("www.")
        return s.takeIf { it.contains('.') && it.length > 3 }
    }

    fun isWhitelisted(link: Link): Boolean =
        WHITELIST_SUFFIXES.any { link.host == it || link.host.endsWith(".$it") }

    fun isShortener(link: Link): Boolean = link.host in SHORTENERS

    /**
     * Suspicious shape (spec 04b §5.5): bare IP host, a run of 4+ digits in the host, an odd TLD, or
     * long labels (benign branch: every label ≤ 17 and host ≤ 25 characters). Whitelisted hosts never are.
     */
    fun isSuspiciousShape(link: Link): Boolean {
        if (isWhitelisted(link)) return false
        val h = link.host
        if (IP_HOST.matches(h)) return true
        if (DIGIT_RUN.containsMatchIn(h)) return true
        if (h.substringAfterLast('.') in ODD_TLDS) return true
        val labels = h.split('.')
        return labels.any { it.length > 17 } || h.length > 25
    }

    /** Bare-IP host or odd TLD (class 3 "Suspicious" link, spec 04b §5.3 row 11). */
    fun isOddHost(link: Link): Boolean =
        !isWhitelisted(link) && (IP_HOST.matches(link.host) || link.host.substringAfterLast('.') in ODD_TLDS)
}
