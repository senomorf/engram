package cam.engram.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cam.engram.app.notify.Notifier
import cam.engram.app.ui.EngramRoot
import cam.engram.app.ui.LocalAppContainer
import cam.engram.app.ui.theme.EngramTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openQueue = intent?.getBooleanExtra(Notifier.EXTRA_OPEN_QUEUE, false) == true
        val container = appContainer()
        setContent {
            val prefs by container.settings.settings.collectAsState(initial = null)
            EngramTheme(dynamicColor = prefs?.dynamicColor ?: true) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    EngramRoot(startInQueue = openQueue)
                }
            }
        }
    }
}
