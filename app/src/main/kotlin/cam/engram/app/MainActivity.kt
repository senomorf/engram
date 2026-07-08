package cam.engram.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cam.engram.app.notify.Notifier
import cam.engram.app.ui.EngramRoot
import cam.engram.app.ui.theme.EngramTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openQueue = intent?.getBooleanExtra(Notifier.EXTRA_OPEN_QUEUE, false) == true
        setContent {
            EngramTheme {
                EngramRoot(startInQueue = openQueue)
            }
        }
    }
}
