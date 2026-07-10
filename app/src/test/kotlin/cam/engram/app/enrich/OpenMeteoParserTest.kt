package cam.engram.app.enrich

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Guards the pure Open-Meteo logic now that it is counted coverage (D22). */
@RunWith(RobolectricTestRunner::class)
class OpenMeteoParserTest {
    private fun body(
        temps: String,
        codes: String,
    ) = """{"hourly":{"temperature_2m":[$temps],"weather_code":[$codes]}}"""

    @Test
    fun parsesTheRequestedHour() {
        val reading = OpenMeteoParser.parse(body("1.5,12.5,3.0", "0,61,0"), hour = 1)!!
        assertEquals(12.5, reading.tempC)
        assertEquals("rain", reading.summary)
        assertEquals("open-meteo", reading.source)
    }

    @Test
    fun malformedResponsesYieldNull() {
        assertNull(OpenMeteoParser.parse("{}", 0))
        assertNull(OpenMeteoParser.parse("""{"hourly":{}}""", 0))
        assertNull(OpenMeteoParser.parse("""{"hourly":{"temperature_2m":[1.0]}}""", 0))
        assertNull(OpenMeteoParser.parse(body("1.0", "0"), hour = 5), "an hour past the data must not read")
    }

    @Test
    fun negativeHourNeverFabricatesAReading() {
        // JSONArray.opt on a negative index returns fallbacks (NaN temperature, code 0
        // = "clear"): a reading built from those would be invented, not observed
        assertNull(OpenMeteoParser.parse(body("1.0,2.0", "0,0"), hour = -3))
    }

    @Test
    fun shortWeatherCodeArrayNeverFabricatesClear() {
        // temperatures cover the hour but the code array does not: optInt would return
        // 0 and report "clear" for weather that was never observed
        assertNull(OpenMeteoParser.parse(body("1.0,2.0,3.0", "61"), hour = 2))
    }

    @Test
    fun everySummaryGroupMaps() {
        assertEquals("clear", OpenMeteoParser.codeToSummary(0))
        assertEquals("partly cloudy", OpenMeteoParser.codeToSummary(2))
        assertEquals("fog", OpenMeteoParser.codeToSummary(45))
        assertEquals("rain", OpenMeteoParser.codeToSummary(61))
        assertEquals("snow", OpenMeteoParser.codeToSummary(75))
        assertEquals("showers", OpenMeteoParser.codeToSummary(81))
        assertEquals("thunderstorm", OpenMeteoParser.codeToSummary(96))
        assertEquals("code 30", OpenMeteoParser.codeToSummary(30))
    }

    @Test
    fun hourOfDayStaysWithinTheDayForPreEpochTimestamps() {
        // one millisecond before the epoch is 23:59:59.999 on 1969-12-31
        assertEquals(23, OpenMeteoParser.hourOfDay(-1L))
        assertEquals(0, OpenMeteoParser.hourOfDay(0L))
        assertEquals(9, OpenMeteoParser.hourOfDay(1_720_000_000_000L)) // 2024-07-03T09:46Z
    }

    @Test
    fun isoDateHandlesLeapDaysAndPreEpochDates() {
        assertEquals("2024-02-29", OpenMeteoParser.isoDate(1_709_164_800_000L))
        assertEquals("1969-12-31", OpenMeteoParser.isoDate(-1L))
        assertEquals("1970-01-01", OpenMeteoParser.isoDate(0L))
    }
}
