package cam.engram.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cam.engram.app.ui.HomeScreen
import cam.engram.app.ui.theme.EngramTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsAppNameAndTagline() {
        compose.setContent {
            EngramTheme {
                HomeScreen(onOpenQueue = {})
            }
        }
        compose.onNodeWithText("Engram").assertExists()
        compose.onNodeWithText("Notes and voice, written into the photo itself.").assertExists()
    }
}
