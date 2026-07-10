package cam.engram.app.enrich

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

// returns null for provider failures but never swallows coroutine cancellation.
// the instanceof-rethrow is the standard idiom for this; the two-catch
// alternative trips RethrowCaughtException, so one rule must be suppressed
@Suppress("InstanceOfCheckForException")
internal inline fun <T> nullOnError(block: () -> T): T? =
    try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

/** Platform reverse geocoder: no API key, on-device where the OEM supports it. */
class GeocoderPlaceProvider(
    context: Context,
) : PlaceProvider {
    private val geocoder = Geocoder(context, Locale.getDefault())

    override suspend fun place(
        lat: Double,
        lon: Double,
    ): String? =
        nullOnError {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(
                        lat,
                        lon,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: List<android.location.Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull()?.toName())
                            }

                            // resume instead of hanging until the enrichment timeout when the
                            // platform geocoder backend errors (common on no-backend devices), so a
                            // place failure no longer stalls the request or blocks weather enrichment
                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.toName()
                }
            }
        }

    private fun android.location.Address.toName(): String? = locality ?: subAdminArea ?: adminArea ?: countryName
}

/**
 * Open-Meteo: free, keyless, supports historical hourly weather so a photo
 * taken yesterday still gets the right conditions.
 */
class OpenMeteoWeatherProvider(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : WeatherProvider {
    override suspend fun weather(
        lat: Double,
        lon: Double,
        atMillis: Long,
    ): WeatherReading? =
        withContext(Dispatchers.IO) {
            nullOnError {
                val date = OpenMeteoParser.isoDate(atMillis)
                val url =
                    "https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                        "&start_date=$date&end_date=$date&hourly=temperature_2m,weather_code&timezone=UTC"
                val body = fetch(url) ?: return@nullOnError null
                OpenMeteoParser.parse(body, OpenMeteoParser.hourOfDay(atMillis))
            }
        }

    private fun fetch(url: String): String? {
        val conn = openConnection(url)
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8000
    }
}
