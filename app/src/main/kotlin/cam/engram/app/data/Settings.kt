package cam.engram.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("engram-settings")

data class EngramSettings(
    val includeScreenshots: Boolean = true,
    val digestEnabled: Boolean = true,
    val digestHour: Int = DEFAULT_DIGEST_HOUR,
    val burstNudgeEnabled: Boolean = false,
    val enrichmentNetworkEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_DIGEST_HOUR = 20
    }
}

class SettingsStore(
    private val context: Context,
) {
    private object Keys {
        val screenshots = booleanPreferencesKey("include_screenshots")
        val digest = booleanPreferencesKey("digest_enabled")
        val digestHour = intPreferencesKey("digest_hour")
        val burst = booleanPreferencesKey("burst_enabled")
        val enrichmentNetwork = booleanPreferencesKey("enrichment_network")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<EngramSettings> =
        context.dataStore.data.map { it.toSettings() }

    suspend fun current(): EngramSettings = settings.first()

    suspend fun setIncludeScreenshots(value: Boolean) = put(Keys.screenshots, value)

    suspend fun setDigestEnabled(value: Boolean) = put(Keys.digest, value)

    suspend fun setDigestHour(value: Int) = put(Keys.digestHour, value.coerceIn(0, 23))

    suspend fun setBurstNudge(value: Boolean) = put(Keys.burst, value)

    suspend fun setEnrichmentNetwork(value: Boolean) = put(Keys.enrichmentNetwork, value)

    suspend fun setOnboardingDone(value: Boolean) = put(Keys.onboarding, value)

    private suspend fun <T> put(
        key: Preferences.Key<T>,
        value: T,
    ) {
        context.dataStore.edit { it[key] = value }
    }

    private fun Preferences.toSettings() =
        EngramSettings(
            includeScreenshots = this[Keys.screenshots] ?: true,
            digestEnabled = this[Keys.digest] ?: true,
            digestHour = this[Keys.digestHour] ?: EngramSettings.DEFAULT_DIGEST_HOUR,
            burstNudgeEnabled = this[Keys.burst] ?: false,
            enrichmentNetworkEnabled = this[Keys.enrichmentNetwork] ?: true,
            onboardingDone = this[Keys.onboarding] ?: false,
        )
}
