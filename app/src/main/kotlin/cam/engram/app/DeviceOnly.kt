package cam.engram.app

/**
 * Marks code that can only run on a real device or emulator, not on the JVM/Robolectric
 * (MediaPlayer playback, SpeechRecognizer dictation). Kover excludes it from the counted
 * line coverage (design D22); the instrumented androidTest layer exercises it instead.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class DeviceOnly
