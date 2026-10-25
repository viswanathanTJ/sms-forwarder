package com.viswa2k.smsforwarder.core

/** Phone-number normalisation for loop-guard comparisons. */
object PhoneNumbers {
    /**
     * Numbers: last 10 digits (all digits if fewer). Alphanumeric sender IDs
     * (anything containing a letter): uppercased, otherwise unchanged.
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.any { it.isLetter() }) return s.uppercase()
        val digits = s.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /** True when both normalise to the same non-empty value. */
    fun same(a: String, b: String): Boolean {
        val na = normalize(a)
        return na.isNotEmpty() && na == normalize(b)
    }
}
