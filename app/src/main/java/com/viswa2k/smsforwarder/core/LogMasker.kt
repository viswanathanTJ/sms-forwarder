package com.viswa2k.smsforwarder.core

/** Hides OTP codes in stored log bodies (spec 5.7). */
object LogMasker {
    private const val DOT = '•'
    private val digitRun = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** Every occurrence of [otp] becomes `•` x its length; with no code, 4-8 digit runs are masked. */
    fun mask(body: String, otp: String?): String =
        if (otp.isNullOrEmpty()) {
            digitRun.replace(body) { DOT.toString().repeat(it.value.length) }
        } else {
            body.replace(otp, DOT.toString().repeat(otp.length))
        }
}
