package cam.engram.app

import cam.engram.app.ui.Dictation
import org.junit.Test
import kotlin.test.assertEquals

class DictationTest {
    @Test
    fun mapsShippedLanguagesToRegionedTags() {
        assertEquals("ru-RU", Dictation.recognizerLanguageTag("ru"))
        assertEquals("en-US", Dictation.recognizerLanguageTag("en"))
        assertEquals("ru-RU", Dictation.recognizerLanguageTag("ru-RU"))
        assertEquals("en-US", Dictation.recognizerLanguageTag("en-GB"))
    }

    @Test
    fun passesThroughUnknownLanguages() {
        assertEquals("fr", Dictation.recognizerLanguageTag("fr"))
    }

    @Test
    fun resolveDictationTagPrefersExplicitChoiceOverUiLanguage() {
        // English UI but the user chose to record Russian (review item 4)
        assertEquals("ru-RU", Dictation.resolveDictationTag("ru-RU", "en"))
        // no explicit choice falls back to the UI language
        assertEquals("ru-RU", Dictation.resolveDictationTag(null, "ru"))
        assertEquals("en-US", Dictation.resolveDictationTag(null, "en"))
    }

    @Test
    fun shortLabelComesFromSupportedListWithFallback() {
        assertEquals("RU", Dictation.shortLabel("ru-RU"))
        assertEquals("EN", Dictation.shortLabel("en-US"))
        assertEquals("PT", Dictation.shortLabel("pt-BR"))
    }

    @Test
    fun supportedLanguagesCoverBothUiLanguages() {
        val tags = Dictation.supportedLanguages.map { it.tag }
        assertEquals(true, tags.containsAll(listOf("en-US", "ru-RU")))
    }

    @Test
    fun mergeAppendsWithoutDoublingSpaces() {
        assertEquals("hello world", Dictation.merge("hello", "world"))
        assertEquals("hi", Dictation.merge("", "  hi  "))
        assertEquals("keep", Dictation.merge("keep", ""))
        assertEquals("a b", Dictation.merge("a ", " b"))
    }
}
