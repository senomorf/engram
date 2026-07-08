package cam.engram.app.ui

/** Pure dictation helpers, kept free of Android types so they unit-test plainly. */
object Dictation {
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

    /** Appends a spoken phrase to existing note text without doubling spaces. */
    fun merge(
        existing: String,
        spoken: String,
    ): String = listOf(existing.trim(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(" ")
}
