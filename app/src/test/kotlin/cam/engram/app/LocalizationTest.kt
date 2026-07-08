package cam.engram.app

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards that every shipped locale stays complete and consistent. Parses the
 * string resources directly (no Android runtime) so a missing or malformed
 * translation fails fast in the unit suite, alongside AGP lint's MissingTranslation.
 */
class LocalizationTest {
    private data class Res(
        val strings: Map<String, String>,
        val plurals: Set<String>,
        val nonTranslatable: Set<String>,
    )

    private fun resFile(rel: String): File =
        listOf(rel, "app/$rel").map(::File).firstOrNull { it.exists() }
            ?: error("cannot find $rel from ${File(".").absolutePath}")

    private fun parse(rel: String): Res {
        val doc =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(resFile(rel))
        val strings = mutableMapOf<String, String>()
        val nonTranslatable = mutableSetOf<String>()
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val e = stringNodes.item(i) as Element
            strings[e.getAttribute("name")] = e.textContent
            if (e.getAttribute("translatable") == "false") nonTranslatable.add(e.getAttribute("name"))
        }
        val plurals = mutableSetOf<String>()
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            plurals.add((pluralNodes.item(i) as Element).getAttribute("name"))
        }
        return Res(strings, plurals, nonTranslatable)
    }

    private val en = parse("src/main/res/values/strings.xml")
    private val ru = parse("src/main/res/values-ru/strings.xml")

    @Test
    fun everyTranslatableEnglishStringHasRussian() {
        val missing = (en.strings.keys - en.nonTranslatable) - ru.strings.keys
        assertTrue(missing.isEmpty(), "Russian is missing translations for: $missing")
    }

    @Test
    fun russianHasNoStringsAbsentFromEnglish() {
        val extra = ru.strings.keys - en.strings.keys
        assertTrue(extra.isEmpty(), "Russian has keys not present in English: $extra")
    }

    @Test
    fun pluralsMatchAcrossLocales() {
        assertEquals(en.plurals, ru.plurals)
    }

    @Test
    fun formatSpecifiersMatchPerKey() {
        val fmt = Regex("""%\d+\$[a-zA-Z]""")
        for (key in en.strings.keys - en.nonTranslatable) {
            val ruValue = ru.strings[key] ?: continue
            val enArgs = fmt.findAll(en.strings.getValue(key)).map { it.value }.toSet()
            val ruArgs = fmt.findAll(ruValue).map { it.value }.toSet()
            assertEquals(enArgs, ruArgs, "format arguments differ for '$key'")
        }
    }
}
