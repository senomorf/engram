package cam.engram.app

import cam.engram.app.ui.Dictation
import kotlin.test.assertEquals
import org.junit.Test

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
    fun mergeAppendsWithoutDoublingSpaces() {
        assertEquals("hello world", Dictation.merge("hello", "world"))
        assertEquals("hi", Dictation.merge("", "  hi  "))
        assertEquals("keep", Dictation.merge("keep", ""))
        assertEquals("a b", Dictation.merge("a ", " b"))
    }
}
