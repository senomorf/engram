package cam.engram.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import cam.engram.app.work.DigestWorker
import cam.engram.app.work.MediaObserverService
import cam.engram.app.work.ReconcileWorker
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EngramApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReconcileWorker.schedulePeriodic(this)
        MediaObserverService.schedule(this)
        // read settings off the main thread so Application.onCreate never blocks
        // on a cold DataStore read (review F2)
        appScope.launch {
            val s = container.settings.current()
            DigestWorker.reschedule(this@EngramApp, s.digestHour, s.digestEnabled)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}

fun Context.appContainer(): AppContainer = (applicationContext as EngramApp).container
