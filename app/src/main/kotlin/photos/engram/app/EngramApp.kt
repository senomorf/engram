package photos.engram.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import photos.engram.app.work.ReconcileWorker

class EngramApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReconcileWorker.schedulePeriodic(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}

fun Context.appContainer(): AppContainer = (applicationContext as EngramApp).container
