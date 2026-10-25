// Ported from SMS app: app/src/main/java/com/viswa2k/sms/finance/Rx.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.finance

/** Small regexes shared by the finance parser, compiled once. */
internal object Rx {
    val PAN = Regex("(?i)\\Wpan\\W")
    val AY = Regex("\\bAY\\b")
    val CURRENCY_PREFIX = Regex("(?i)^(rs|inr)")
    val SPACES = Regex("\\s+")
    val NON_NUMBER = Regex("[^0-9.]")
    val DIGITS2 = Regex("[0-9]{2,}")
    val MULTI_SPACE = Regex("[ \\t]{2,}")
    val NON_WORDS = Regex("\\W+")
    val WORD_STAR_DIGITS = Regex("^(\\w+)(\\*|_)\\d+")
    val LEADING_YOUR = Regex("(?i)^your\\s+")
    val STARS = Regex("[*_]+")
    val NON_ALNUM_SLASH = Regex("[^A-Za-z0-9/]+")
    val AT = Regex("(?i)\\Wat\\W+")
    val TOWARDS = Regex("(?i)towards")
    val DASH_DIGITS_END = Regex("-[0-9]+$")
    val UNDERSCORES = Regex("_{2,}")
    val Q_DIGITS = Regex("(?i)^(q)[0-9]+")
    val PAYTM = Regex("(?i)paytmqr|paytm")
    val UPI_SEGMENT_WORD = Regex("(?i)p2[apm]|dr|cr|upi|imps|neft")
    val MOBILE_PREFIX = Regex("^[0-9]{10}")
    val DECIMAL = Regex("\\d\\.\\d")
    val DIGIT_RUN = Regex("[0-9]+")
}
