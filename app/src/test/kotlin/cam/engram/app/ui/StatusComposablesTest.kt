package cam.engram.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.export.ExportResult
import cam.engram.app.ui.theme.EngramTheme
import cam.engram.format.read.Survival
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The pure status renderers, previously @DeviceOnly by over-caution: they render
 * plain state and are fully exercisable on the JVM (D22). The launcher callbacks
 * that feed them stay device-only.
 */
@RunWith(RobolectricTestRunner::class)
class StatusComposablesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun str(
        id: Int,
        vararg args: Any,
    ) = context.getString(id, *args)

    @Test
    fun exportStatusRendersProgressResultAndFailure() {
        compose.setContent {
            EngramTheme {
                Column {
                    ExportStatus(ExportState.Idle)
                    ExportStatus(ExportState.Running)
                    ExportStatus(ExportState.Done(ExportResult(3, 2, 1)))
                    ExportStatus(ExportState.Failed("boom"))
                }
            }
        }
        compose.onNodeWithText(str(R.string.tools_exporting)).assertExists()
        compose.onNodeWithText(str(R.string.tools_export_done, 3, 2)).assertExists()
        compose.onNodeWithText(str(R.string.tools_export_partial, 1)).assertExists()
        compose.onNodeWithText(str(R.string.tools_export_failed, "boom")).assertExists()
    }

    @Test
    fun verifyStatusRendersEverySurvivalVerdict() {
        compose.setContent {
            EngramTheme {
                Column {
                    VerifyStatus(VerifyState.Idle)
                    VerifyStatus(VerifyState.Running)
                    VerifyStatus(VerifyState.Done(Survival.FULL, audioClips = 2, corruptCount = 0))
                    VerifyStatus(VerifyState.Done(Survival.DAMAGED, audioClips = 0, corruptCount = 3))
                    VerifyStatus(VerifyState.Done(Survival.CAPTION_ONLY, audioClips = 0, corruptCount = 0))
                    VerifyStatus(VerifyState.Done(Survival.GONE, audioClips = 0, corruptCount = 0))
                    VerifyStatus(VerifyState.Done(Survival.UNREADABLE, audioClips = 0, corruptCount = 0))
                }
            }
        }
        compose.onNodeWithText(str(R.string.tools_verifying)).assertExists()
        compose.onNodeWithText(str(R.string.tools_survival_full, 2)).assertExists()
        compose.onNodeWithText(str(R.string.tools_survival_damaged, 3)).assertExists()
        compose.onNodeWithText(str(R.string.tools_survival_caption)).assertExists()
        compose.onNodeWithText(str(R.string.tools_survival_gone)).assertExists()
        compose.onNodeWithText(str(R.string.tools_survival_unreadable)).assertExists()
    }

    @Test
    fun dictationControlsRouteBothCallbacks() {
        var picked: String? = null
        var dictated = 0
        compose.setContent {
            EngramTheme {
                DictationControls(
                    dictationTag = Dictation.supportedLanguages.first().tag,
                    onPickLanguage = { picked = it },
                    onDictate = { dictated++ },
                )
            }
        }
        compose.onNodeWithContentDescription(str(R.string.annotate_dictate)).performClick()
        assertEquals(1, dictated)
        compose.onNodeWithText(Dictation.shortLabel(Dictation.supportedLanguages.first().tag)).performClick()
        compose.onNodeWithText(Dictation.supportedLanguages.last().label).performClick()
        assertNotNull(picked, "picking a language from the menu must reach the callback")
        assertEquals(Dictation.supportedLanguages.last().tag, picked)
    }
}
