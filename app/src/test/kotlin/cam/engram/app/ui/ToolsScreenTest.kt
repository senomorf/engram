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
}
