package com.viswa2k.smsforwarder.core

/** Hides OTP codes in stored log text (spec 5.7). */
object LogMasker {
    private const val DOT = '•'
    // 4-8 digits, optionally split by single spaces or hyphens ("482-913"); separators are kept.
    private val digitRun = Regex("(?<!\\d)\\d(?:[ -]?\\d){3,7}(?!\\d)")

    /**
     * Masks [text]: when [otp] is known and present, every occurrence becomes `•` x its length;
     * otherwise (no code, or the code is not literally in the text) 4-8 digit runs are masked.
     */
    fun mask(text: String, otp: String?): String =
        if (!otp.isNullOrEmpty() && text.contains(otp)) {
            text.replace(otp, DOT.toString().repeat(otp.length))
        } else {
            digitRun.replace(text) { m -> m.value.map { if (it.isDigit()) DOT else it }.joinToString("") }
        }
}
