package cam.engram.app.enrich

import org.json.JSONObject
import java.util.Locale

/**
 * Pure Open-Meteo request/response logic, split from the provider so the
 * network fetch stays the only device-excluded part and this logic counts
 * toward measured coverage (D22).
 */
internal object OpenMeteoParser {
    fun parse(
        body: String,
        hour: Int,
    ): WeatherReading? {
        // out-of-range indexes make JSONArray.opt return fallbacks (NaN temperature,
        // code 0 = "clear"): that would fabricate a reading nobody observed
        if (hour < 0) return null
        val hourly = JSONObject(body).optJSONObject("hourly") ?: return null
        val temps = hourly.optJSONArray("temperature_2m") ?: return null
        val codes = hourly.optJSONArray("weather_code") ?: return null
        if (hour >= temps.length() || hour >= codes.length()) return null
        return WeatherReading(codeToSummary(codes.optInt(hour)), temps.optDouble(hour), "open-meteo")
    }

    // WMO weather interpretation codes, condensed to human words
    fun codeToSummary(code: Int): String =
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

    fun isoDate(millis: Long): String {
        // floorDiv, not truncation, so pre-epoch timestamps land on the right civil day
        val days = millis.floorDiv(86_400_000L)
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
        // Locale.US so non-ASCII locale digits never corrupt the request URL (review F12)
        return String.format(Locale.US, "%04d-%02d-%02d", year, m, d)
    }

    // floorMod keeps the hour in 0..23 for pre-epoch timestamps too
    fun hourOfDay(millis: Long): Int = millis.floorDiv(3_600_000L).mod(24L).toInt()
}
