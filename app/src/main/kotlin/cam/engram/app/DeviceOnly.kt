package cam.engram.app

/**
 * Marks code that can only run on a real device or emulator, not on the JVM/Robolectric
 * (MediaPlayer playback, SpeechRecognizer dictation). Kover excludes it from the counted
 * line coverage (design D22). Device coverage comes from two lanes: instrumented
 * androidTest exercises ResolverContentAccess, MediaStoreSource, and SafArchiveSink;
 * everything else that is annotated or excluded (MediaPlayer playback, dictation,
 * Geocoder, the Open-Meteo fetch, MediaObserverService) is checked manually via
 * docs/device-qa.md. No annotated composable is instrumented.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class DeviceOnly
