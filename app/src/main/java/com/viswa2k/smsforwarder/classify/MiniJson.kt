// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/MiniJson.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

/**
 * Minimal JSON reader (objects, arrays, strings, numbers, booleans, null) for the bundled keyword
 * asset. Pure Kotlin so the classifier stays free of Android types (org.json is a platform class).
 * Objects become [LinkedHashMap], arrays [ArrayList], numbers [Double] or [Long].
 */
internal class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val v = value()
            ws()
            require(i == s.length) { "Trailing data at $i" }
            v
        }
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "Unexpected end of JSON" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $i")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        require(s.startsWith(word, i)) { "Bad literal at $i" }
        i += word.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws()
            val k = str()
            ws()
            require(s[i] == ':') { "Expected ':' at $i" }
            i++
            out[k] = value()
            ws()
            when (s[i++]) {
                ',' -> continue
                '}' -> return out
                else -> error("Expected ',' or '}' at ${i - 1}")
            }
        }
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out += value()
            ws()
            when (s[i++]) {
                ',' -> continue
                ']' -> return out
                else -> error("Expected ',' or ']' at ${i - 1}")
            }
        }
    }

    private fun str(): String {
        require(s[i] == '"') { "Expected string at $i" }
        i++
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("Bad escape '\\$e' at $i")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Number {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}
