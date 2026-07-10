package cam.engram.app.ui

import org.junit.Test
import kotlin.test.assertEquals

class SpeechInputLogicTest {
    @Test
    fun neverChoosesRemoteWithoutConsent() {
        // no recognition service at all
        assertEquals(
            RecognizerChoice.None,
            chooseRecognizer(recognitionAvailable = false, onDeviceAvailable = false, remoteConsent = false),
        )
        // on-device is always preferred and needs no consent
        assertEquals(
            RecognizerChoice.OnDevice,
            chooseRecognizer(recognitionAvailable = true, onDeviceAvailable = true, remoteConsent = false),
        )
        // remote-only without consent must not pick the remote recognizer (finding 6)
        assertEquals(
            RecognizerChoice.None,
            chooseRecognizer(recognitionAvailable = true, onDeviceAvailable = false, remoteConsent = false),
        )
        // remote-only with consent picks remote
        assertEquals(
            RecognizerChoice.Remote,
            chooseRecognizer(recognitionAvailable = true, onDeviceAvailable = false, remoteConsent = true),
        )
    }
}
