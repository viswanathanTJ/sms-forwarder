// Ported from SMS app: app/src/main/java/com/viswa2k/sms/classify/TextFolding.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import java.text.Normalizer

/**
 * Body normalisation before the keyword rules (spec 04b §4.1 step 1 and §4.2 step 4): NFKC folds
 * full-width / mathematical "styled" letters; [foldLookAlikes] maps Cyrillic / Greek look-alike letters
 * to Latin so evasive spellings hit the same regexes.
 */
object TextFolding {

    private val LOOK_ALIKES: Map<Char, Char> = buildMap {
        // Cyrillic
        "аa еe оo рp сc хx уy іi јj ѕs ԁd һh ӏl ԛq ԝw".chunked(3).forEach { put(it[0], it[1]) }
        "АA ВB ЕE КK МM НH ОO РP СC ТT ХX ІI ЈJ ЅS".chunked(3).forEach { put(it[0], it[1]) }
        // Greek
        "οo αa ιi κk νv ρp τt υu χx".chunked(3).forEach { put(it[0], it[1]) }
        "ΑA ΒB ΕE ΖZ ΗH ΙI ΚK ΜM ΝN ΟO ΡP ΤT ΥY ΧX".chunked(3).forEach { put(it[0], it[1]) }
    }

    /** NFKC form of [text]. */
    fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /** Replaces look-alike letters with their Latin twins. */
    fun foldLookAlikes(text: String): String {
        if (text.none { it in LOOK_ALIKES }) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(LOOK_ALIKES[c] ?: c)
        return sb.toString()
    }

    /** NFKC + look-alike folding. */
    fun fold(text: String): String = foldLookAlikes(nfkc(text))

    /** True if [text] contains letters that [fold] would change (styled Unicode / homoglyphs). */
    fun hasStyledText(text: String): Boolean = fold(text) != text
}
