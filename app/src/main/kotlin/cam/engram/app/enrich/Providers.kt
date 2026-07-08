package cam.engram.app.enrich

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** Platform reverse geocoder: no API key, on-device where the OEM supports it. */
class GeocoderPlaceProvider(
    context: Context,
) : PlaceProvider {
    private val geocoder = Geocoder(context, Locale.getDefault())

    override suspend fun place(
        lat: Double,
        lon: Double,
    ): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1) { list -> cont.resume(list.firstOrNull()?.toName()) }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.toName()
                }
            }
        }.getOrNull()

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
            runCatching {
                val date = isoDate(atMillis)
                val url =
                    "https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                        "&start_date=$date&end_date=$date&hourly=temperature_2m,weather_code&timezone=UTC"
                val body = fetch(url) ?: return@runCatching null
                parse(body, hourOfDay(atMillis))
            }.getOrNull()
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

    private fun parse(
        body: String,
        hour: Int,
    ): WeatherReading? {
        val hourly = JSONObject(body).optJSONObject("hourly") ?: return null
        val temps = hourly.optJSONArray("temperature_2m") ?: return null
        val codes = hourly.optJSONArray("weather_code") ?: return null
        if (hour >= temps.length()) return null
        val temp = temps.optDouble(hour)
        val code = codes.optInt(hour)
        return WeatherReading(codeToSummary(code), temp, "open-meteo")
    }

    // WMO weather interpretation codes, condensed to human words
    private fun codeToSummary(code: Int): String =
        when (code) {
            0 -> "clear"
            1, 2, 3 -> "partly cloudy"
            in 45..48 -> "fog"
            in 51..67 -> "rain"
            in 71..77 -> "snow"
            in 80..82 -> "showers"
            in 95..99 -> "thunderstorm"
            else -> "code $code"
        }

    private fun isoDate(millis: Long): String {
        val days = millis / 86_400_000L
        // civil date from epoch days (Howard Hinnant's algorithm)
        var z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return "%04d-%02d-%02d".format(year, m, d)
    }

    private fun hourOfDay(millis: Long): Int = ((millis / 3_600_000L) % 24).toInt()

    private companion object {
        const val TIMEOUT_MS = 8000
    }
}
