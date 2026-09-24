package com.livetranslate.headphones.translation

import com.google.mlkit.nl.translate.TranslateLanguage

object SupportedLanguages {
    data class LanguageOption(
        val code: String,
        val label: String,
    )

    val options = listOf(
        LanguageOption(TranslateLanguage.SPANISH, "Spanish"),
        LanguageOption(TranslateLanguage.FRENCH, "French"),
        LanguageOption(TranslateLanguage.GERMAN, "German"),
        LanguageOption(TranslateLanguage.ITALIAN, "Italian"),
        LanguageOption(TranslateLanguage.PORTUGUESE, "Portuguese"),
        LanguageOption(TranslateLanguage.KOREAN, "Korean"),
        LanguageOption(TranslateLanguage.JAPANESE, "Japanese"),
        LanguageOption(TranslateLanguage.CHINESE, "Chinese"),
        LanguageOption(TranslateLanguage.ARABIC, "Arabic"),
        LanguageOption(TranslateLanguage.HINDI, "Hindi"),
        LanguageOption(TranslateLanguage.RUSSIAN, "Russian"),
        LanguageOption(TranslateLanguage.VIETNAMESE, "Vietnamese"),
        LanguageOption(TranslateLanguage.THAI, "Thai"),
        LanguageOption(TranslateLanguage.TURKISH, "Turkish"),
        LanguageOption(TranslateLanguage.POLISH, "Polish"),
        LanguageOption(TranslateLanguage.DUTCH, "Dutch"),
        LanguageOption(TranslateLanguage.SWEDISH, "Swedish"),
        LanguageOption(TranslateLanguage.INDONESIAN, "Indonesian"),
        LanguageOption(TranslateLanguage.UKRAINIAN, "Ukrainian"),
        LanguageOption(TranslateLanguage.GREEK, "Greek"),
    )

    fun labelFor(code: String): String =
        options.firstOrNull { it.code == code }?.label ?: code

    // Maps an ML Kit translate language code (e.g. "es") to a BCP-47 tag that the
    // Android SpeechRecognizer expects (e.g. "es-ES"). Used to build the candidate
    // set for automatic language switching.
    fun recognizerTag(code: String): String = when (code) {
        TranslateLanguage.SPANISH -> "es-ES"
        TranslateLanguage.FRENCH -> "fr-FR"
        TranslateLanguage.GERMAN -> "de-DE"
        TranslateLanguage.ITALIAN -> "it-IT"
        TranslateLanguage.PORTUGUESE -> "pt-BR"
        TranslateLanguage.KOREAN -> "ko-KR"
        TranslateLanguage.JAPANESE -> "ja-JP"
        // Android's on-device recognizer identifies Mandarin as "cmn-Hans-CN", not "zh-CN".
        TranslateLanguage.CHINESE -> "cmn-Hans-CN"
        TranslateLanguage.ARABIC -> "ar-EG"
        TranslateLanguage.HINDI -> "hi-IN"
        TranslateLanguage.RUSSIAN -> "ru-RU"
        TranslateLanguage.VIETNAMESE -> "vi-VN"
        TranslateLanguage.THAI -> "th-TH"
        TranslateLanguage.TURKISH -> "tr-TR"
        TranslateLanguage.POLISH -> "pl-PL"
        TranslateLanguage.DUTCH -> "nl-NL"
        TranslateLanguage.SWEDISH -> "sv-SE"
        TranslateLanguage.INDONESIAN -> "id-ID"
        TranslateLanguage.UKRAINIAN -> "uk-UA"
        TranslateLanguage.GREEK -> "el-GR"
        else -> code
    }

    const val ENGLISH_TAG = "en-US"
}
