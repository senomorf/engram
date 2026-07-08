package cam.engram.app.ui

/** Pure dictation helpers, kept free of Android types so they unit-test plainly. */
object Dictation {
    /** A language the recognizer can be asked to use, independent of the UI language. */
    data class Language(
        val tag: String,
        val label: String,
        val short: String,
    )

    // curated and extensible; recording language is decoupled from the UI language,
    // so any of these can be chosen regardless of which language the app is shown in
    val supportedLanguages =
        listOf(
            Language("en-US", "English", "EN"),
            Language("ru-RU", "Русский", "RU"),
            Language("uk-UA", "Українська", "UK"),
            Language("es-ES", "Español", "ES"),
            Language("de-DE", "Deutsch", "DE"),
            Language("fr-FR", "Français", "FR"),
        )

    /**
     * Maps an app language tag (e.g. "en", "ru", "en-US") to a BCP-47 tag the
     * speech recognizer accepts, adding the region we ship strings for so the
     * on-device model is selected correctly.
     */
    fun recognizerLanguageTag(appLanguageTag: String): String =
        when (appLanguageTag.substringBefore('-').lowercase()) {
            "ru" -> "ru-RU"
            "en" -> "en-US"
            else -> appLanguageTag
        }

    /** The recording language: an explicit user choice, else the UI language. */
    fun resolveDictationTag(
        preferred: String?,
        appLanguageTag: String,
    ): String = preferred ?: recognizerLanguageTag(appLanguageTag)

    /** Short badge for a recording tag, e.g. "EN"; falls back to the primary subtag. */
    fun shortLabel(tag: String): String =
        supportedLanguages.firstOrNull { it.tag == tag }?.short
            ?: tag.substringBefore('-').uppercase()

    /** Appends a spoken phrase to existing note text without doubling spaces. */
    fun merge(
        existing: String,
        spoken: String,
    ): String = listOf(existing.trim(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(" ")
}
