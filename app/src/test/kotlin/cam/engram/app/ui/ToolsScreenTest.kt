package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.setScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = app.db.close()

    @Test
    fun showsExportAndVerifyActions() {
        compose.setScreen(app) { ToolsScreen(onBack = {}) }
        compose.onNodeWithText(strings.getString(R.string.tools_export_button)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.tools_verify_button)).assertIsDisplayed()
    }

    @Test
    fun exportStatusRendersDoneWithPartialFailures() {
        compose.setScreen(app) {
            ExportStatus(
                ExportState.Done(
                    cam.engram.app.export
                        .ExportResult(3, 2, 1),
                ),
            )
        }
        compose.onNodeWithText(strings.getString(R.string.tools_export_done, 3, 2)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.tools_export_partial, 1)).assertIsDisplayed()
    }

    @Test
    fun exportStatusRendersFailureWithFallbackMessage() {
        compose.setScreen(app) { ExportStatus(ExportState.Failed(null)) }
        compose
            .onNodeWithText(
                strings.getString(R.string.tools_export_failed, strings.getString(R.string.error_unknown)),
            ).assertIsDisplayed()
    }

    @Test
    fun verifyStatusRendersSurvival() {
        compose.setScreen(app) {
            VerifyStatus(VerifyState.Done(cam.engram.format.read.Survival.FULL, 2, 0))
        }
        compose.onNodeWithText(strings.getString(R.string.tools_survival_full, 2)).assertIsDisplayed()
    }

    @Test
    fun verifyStatusRendersIncompleteSurvival() {
        compose.setScreen(app) {
            VerifyStatus(VerifyState.Done(cam.engram.format.read.Survival.INCOMPLETE, 1, 0))
        }
        compose.onNodeWithText(strings.getString(R.string.tools_survival_incomplete)).assertIsDisplayed()
    }
}
