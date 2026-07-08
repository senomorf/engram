package cam.engram.app.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers the color-scheme selection branches (dynamic vs brand, dark vs light). */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun brandDarkScheme() {
        compose.setContent { EngramTheme(dynamicColor = false, darkTheme = true) { Text("brand-dark") } }
        compose.onNodeWithText("brand-dark").assertExists()
    }

    @Test
    fun brandLightScheme() {
        compose.setContent { EngramTheme(dynamicColor = false, darkTheme = false) { Text("brand-light") } }
        compose.onNodeWithText("brand-light").assertExists()
    }

    @Test
    fun dynamicDarkScheme() {
        compose.setContent { EngramTheme(dynamicColor = true, darkTheme = true) { Text("dynamic-dark") } }
        compose.onNodeWithText("dynamic-dark").assertExists()
    }
}
