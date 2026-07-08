package cam.engram.app

import cam.engram.app.enrich.Enricher
import cam.engram.app.enrich.GpsContext
import cam.engram.app.enrich.OpenMeteoWeatherProvider
import cam.engram.app.enrich.PlaceProvider
import cam.engram.app.enrich.WeatherProvider
import cam.engram.app.enrich.WeatherReading
import cam.engram.format.records.EnrichmentPayload
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EnricherTest {
    @Test
    fun assemblesFieldsWithProvenance() =
        runBlocking {
            val enricher =
                Enricher(
                    place =
                        object : PlaceProvider {
                            override suspend fun place(
                                lat: Double,
                                lon: Double,
                            ) = "Batumi"
                        },
                    weather =
                        object : WeatherProvider {
                            override suspend fun weather(
                                lat: Double,
                                lon: Double,
                                atMillis: Long,
                            ) = WeatherReading("clear", 24.0, "open-meteo")
                        },
                    clock = { 1783000000000 },
                )
            val payload = enricher.enrich(GpsContext(41.6, 41.6, 1782000000000))!!
            assertEquals("Batumi", payload.fields[EnrichmentPayload.KEY_PLACE])
            assertEquals("clear", payload.fields[EnrichmentPayload.KEY_WEATHER])
            assertEquals("geocoder+open-meteo", payload.fields[EnrichmentPayload.KEY_SOURCE])
            assertEquals("1783000000000", payload.fields[EnrichmentPayload.KEY_FETCHED_AT])
        }

    @Test
    fun noProvidersMeansNoRecord() =
        runBlocking {
            val enricher =
                Enricher(
                    place =
                        object : PlaceProvider {
                            override suspend fun place(
                                lat: Double,
                                lon: Double,
                            ): String? = null
                        },
                    weather =
                        object : WeatherProvider {
                            override suspend fun weather(
                                lat: Double,
                                lon: Double,
                                atMillis: Long,
                            ): WeatherReading? = null
                        },
                )
            assertNull(enricher.enrich(GpsContext(0.0, 0.0, 0)))
        }

    @Test
    fun openMeteoParsesArchiveResponse() =
        runBlocking {
            val json =
                """{"hourly":{"temperature_2m":[${List(24) { it }.joinToString(",")}],""" +
                    """"weather_code":[${List(24) { 0 }.joinToString(",")}]}}"""
            val provider =
                OpenMeteoWeatherProvider { url ->
                    FakeConnection(url, json)
                }
            // 12:00 UTC -> hour index 12 -> temp 12.0
            val reading = provider.weather(41.0, 41.0, 12 * 3_600_000L)!!
            assertEquals(12.0, reading.tempC, 0.001)
            assertEquals("clear", reading.summary)
            assertTrue(reading.source == "open-meteo")
        }
}

private class FakeConnection(
    url: String,
    private val body: String,
) : HttpURLConnection(java.net.URL(url)) {
    override fun getResponseCode(): Int = HTTP_OK

    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())

    override fun connect() {}

    override fun disconnect() {}

    override fun usingProxy(): Boolean = false
}
