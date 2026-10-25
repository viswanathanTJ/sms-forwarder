// Ported from SMS app: app/src/test/java/com/viswa2k/sms/classify/TestKeywords.kt (2026-10-25 snapshot)
package com.viswa2k.smsforwarder.classify

import java.io.File

/** Loads the real `assets/keywords.json` from the module directory (Gradle runs unit tests in `app/`). */
object TestKeywords {
    private fun file(): File = listOf(
        File("src/main/assets/keywords.json"),
        File("app/src/main/assets/keywords.json"),
    ).first { it.exists() }

    val source: KeywordSource = KeywordSource { file().readText(Charsets.UTF_8) }

    val lists: KeywordLists by lazy { KeywordLists.load(source) }

    val classifier: MessageClassifier by lazy { MessageClassifier(lists) }
}
