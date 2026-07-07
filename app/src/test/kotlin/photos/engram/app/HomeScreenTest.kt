package photos.engram.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import photos.engram.app.ui.HomeScreen
import photos.engram.app.ui.theme.EngramTheme

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsAppNameAndTagline() {
        compose.setContent {
            EngramTheme {
                HomeScreen()
            }
        }
        compose.onNodeWithText("Engram").assertExists()
        compose.onNodeWithText("Notes and voice, written into the photo itself.").assertExists()
    }
}
