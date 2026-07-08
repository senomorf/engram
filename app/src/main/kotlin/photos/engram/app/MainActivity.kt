package photos.engram.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import photos.engram.app.notify.Notifier
import photos.engram.app.ui.EngramRoot
import photos.engram.app.ui.theme.EngramTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openQueue = intent?.getBooleanExtra(Notifier.EXTRA_OPEN_QUEUE, false) == true
        setContent {
            EngramTheme {
                EngramRoot(startInQueue = openQueue)
            }
        }
    }
}
