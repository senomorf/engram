package photos.engram.app.enrich

import photos.engram.format.records.EnrichmentPayload

interface PlaceProvider {
    /** Human place name for a coordinate, or null when unavailable. */
    suspend fun place(
        lat: Double,
        lon: Double,
    ): String?
}

class WeatherReading(
    val summary: String,
    val tempC: Double,
    val source: String,
)

interface WeatherProvider {
    suspend fun weather(
        lat: Double,
        lon: Double,
        atMillis: Long,
    ): WeatherReading?
}

/**
 * Assembles an enrichment payload from derived GPS context, tagging provenance
 * (source, fetched_at). Network use is the caller's decision; providers here
 * are given only when enrichment is enabled.
 */
class Enricher(
    private val place: PlaceProvider,
    private val weather: WeatherProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enrich(context: GpsContext): EnrichmentPayload? {
        val fields = LinkedHashMap<String, String>()
        val sources = mutableListOf<String>()
        place.place(context.lat, context.lon)?.let {
            fields[EnrichmentPayload.KEY_PLACE] = it
            sources += "geocoder"
        }
        weather.weather(context.lat, context.lon, context.timestampMillis)?.let {
            fields[EnrichmentPayload.KEY_WEATHER] = it.summary
            fields[EnrichmentPayload.KEY_TEMP_C] = it.tempC.toString()
            sources += it.source
        }
        if (fields.isEmpty()) return null
        fields[EnrichmentPayload.KEY_SOURCE] = sources.joinToString("+")
        fields[EnrichmentPayload.KEY_FETCHED_AT] = clock().toString()
        return EnrichmentPayload(fields)
    }
}
